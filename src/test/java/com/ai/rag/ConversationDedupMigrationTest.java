package com.ai.rag;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 迁移专测：先合并重复会话，再给 {@code session_id} 加唯一约束（缺陷 D4）
 *
 * <p><b>为什么必须单独测：</b>其余测试都跑在「Flyway 已经把 V1+V2 依次应用到干净库」的
 * 上下文里，永远碰不到 V2 真正要解决的问题——<b>存量脏数据</b>。而 V2 的合并逻辑
 * （保留哪一行、消息怎么搬、顺序为什么不能反）恰恰只在有重复行时才执行。
 * 不测这一段，等于把一个会改写生产数据的迁移完全交给「读一遍觉得没问题」。
 *
 * <p><b>做法：</b>不启 Spring，自己拿 Flyway API 在独立的 H2 上分两步走——
 * 先只迁到 V1，用裸 JDBC 造出 V1 允许、V2 不容的脏数据，再迁 V2，最后断言合并结果。
 * 验证的是**真实迁移脚本**，不是等价的手写 SQL。
 *
 * <p><b>局限（与 V1 相同）：</b>本机没有 MySQL，{@code mysql/V2__*.sql} 只经过人工审阅，
 * 未经真机执行。两个脚本逐句等价，但 MySQL 的 DDL 不在事务里，中途失败需人工修复。
 */
class ConversationDedupMigrationTest {

    /**
     * 与 {@code test} profile 的 datasource 保持同样的 H2 设置：
     * {@code MODE=MySQL} 让语法贴近生产，{@code NON_KEYWORDS} 避开 H2 保留字，
     * {@code DB_CLOSE_DELAY=-1} 让库在整个 JVM 生命周期内存活。
     */
    private static final String URL =
            "jdbc:h2:mem:dedup-migration;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private Connection connection;

    @BeforeEach
    void migrateToV1OnEmptyDatabase() throws Exception {
        connection = DriverManager.getConnection(URL, USER, PASSWORD);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        migrateTo(MigrationVersion.fromVersion("1"));
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    // ------------------------------------------------------------------
    // 一、合并本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V2：重复会话合并为一条（保留 MIN(id)），两条消息都还在并且都指向保留行")
    void mergesDuplicatesAndKeepsMessages() throws Exception {
        long keep = insertConversation("s-dup", "2026-01-01 10:00:00");
        long duplicate = insertConversation("s-dup", "2026-01-02 11:00:00");
        long solo = insertConversation("s-solo", "2026-01-03 12:00:00");

        insertMessage(keep, "保留行的消息");
        insertMessage(duplicate, "重复行的消息");
        insertMessage(solo, "无关会话的消息");

        assertThat(conversationsOf("s-dup"))
                .as("前提：V1 下确实允许重复 session_id（这就是 D4 的成因）")
                .containsExactly(keep, duplicate);

        migrateRemaining();

        assertThat(conversationsOf("s-dup"))
                .as("合并后只剩 id 最小的那条——它的 created_at 才是这个会话的真实起点")
                .containsExactly(keep);
        assertThat(messagesOf("s-dup"))
                .as("两条消息都必须保留：messages 的外键是 ON DELETE CASCADE，"
                        + "若迁移先删会话再搬消息，这条断言会看到消息被静默级联删除")
                .containsExactlyInAnyOrder("保留行的消息", "重复行的消息");
        assertThat(conversationIdsOfMessages("s-dup"))
                .as("消息必须全部挂在保留行上，否则它们对 findByConversationId 不可见")
                .containsOnly(keep);

        assertThat(conversationsOf("s-solo")).containsExactly(solo);
        assertThat(messagesOf("s-solo")).containsExactly("无关会话的消息");
    }

    @Test
    @DisplayName("V2：tool_calls 也要重新指向保留行——它没有外键保护，漏掉就会留下悬空引用")
    void repointsToolCallsAsWell() throws Exception {
        long keep = insertConversation("s-tool", "2026-01-01 10:00:00");
        long duplicate = insertConversation("s-tool", "2026-01-02 11:00:00");

        insertToolCall(keep, "s-tool", "calculator");
        insertToolCall(duplicate, "s-tool", "datetime");
        insertToolCall(null, "s-unrelated", "weather");

        migrateRemaining();

        assertThat(conversationIdsOfToolCalls("s-tool"))
                .as("两条工具调用记录都要指向保留行；conversation_id 为 NULL 的那条不受影响")
                .containsExactlyInAnyOrder(keep, keep);
        assertThat(toolCallsWithNullConversation())
                .as("NULL 的 conversation_id 不该被迁移误伤（IN 子查询天然不匹配 NULL，这里锁住该行为）")
                .isEqualTo(1);
        assertThat(conversationsOf("s-tool")).containsExactly(keep);
    }

    @Test
    @DisplayName("V2：没有重复行的库也能正常迁移（存量干净时合并步骤是空操作）")
    void handlesDatabaseWithoutDuplicates() throws Exception {
        long only = insertConversation("s-clean", "2026-01-01 10:00:00");
        insertMessage(only, "唯一会话的消息");

        migrateRemaining();

        assertThat(conversationsOf("s-clean")).containsExactly(only);
        assertThat(messagesOf("s-clean")).containsExactly("唯一会话的消息");
        assertThat(tableExists("CONVERSATION_DEDUP"))
                .as("迁移必须能在没有任何重复行的库上跑通，否则线上全是干净库反而升不了级")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 二、约束与脚手架
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V2：唯一约束真的建上了（重复插入被数据库拒绝），且临时映射表已拆除")
    void addsUniqueConstraintAndDropsScaffold() throws Exception {
        long keep = insertConversation("s-dup", "2026-01-01 10:00:00");
        insertConversation("s-dup", "2026-01-02 11:00:00");

        migrateRemaining();

        assertThat(conversationsOf("s-dup")).containsExactly(keep);

        assertThatThrownBy(() -> insertConversation("s-dup", "2026-01-03 13:00:00"))
                .as("约束是 D4 的最后一道防线：绕过业务层直接插也必须失败")
                .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                .satisfies(thrown -> assertThat(
                        ((SQLException) thrown).getSQLState())
                        .as("SQLState 的 '23' 类即「完整性约束冲突」。不写死具体码："
                                + "H2 给 23505、MySQL 给 23000，两库都落在 23 类里")
                        .startsWith("23"));

        assertThat(countRows("conversations")).as("失败的插入不应留下痕迹").isEqualTo(1);
        assertThat(tableExists("CONVERSATION_DEDUP"))
                .as("一次性脚手架必须拆掉，不能留在业务库里")
                .isFalse();
        assertThat(hasUniqueIndexOnSessionId())
                .as("从元数据确认 uk_conversations_session_id 存在且唯一")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Flyway / JDBC helpers
    // ------------------------------------------------------------------

    /** 只迁到指定版本（用于把库停在 V1，好造出 V2 要处理的脏数据） */
    private void migrateTo(MigrationVersion target) {
        Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration/h2")
                .target(target)
                .load()
                .migrate();
    }

    /** 把剩下的迁移全部应用（V1 之后即 V2） */
    private void migrateRemaining() {
        Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate();
    }

    private long insertConversation(String sessionId, String createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO conversations (session_id, title, user_name, model, created_at, updated_at) "
                        + "VALUES (?, '新会话', 'tester', 'gpt-4', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sessionId);
            statement.setString(2, createdAt);
            statement.setString(3, createdAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void insertMessage(long conversationId, String content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO messages (conversation_id, role, content, tokens, created_at) "
                        + "VALUES (?, 'USER', ?, 1, CURRENT_TIMESTAMP)")) {
            statement.setLong(1, conversationId);
            statement.setString(2, content);
            statement.executeUpdate();
        }
    }

    private void insertToolCall(Long conversationId, String sessionId, String toolName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tool_calls (conversation_id, session_id, tool_name, status, created_at) "
                        + "VALUES (?, ?, ?, 'SUCCESS', CURRENT_TIMESTAMP)")) {
            if (conversationId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, conversationId);
            }
            statement.setString(2, sessionId);
            statement.setString(3, toolName);
            statement.executeUpdate();
        }
    }

    private List<Long> conversationsOf(String sessionId) throws SQLException {
        return queryLongs("SELECT id FROM conversations WHERE session_id = ? ORDER BY id", sessionId);
    }

    private List<Long> conversationIdsOfMessages(String sessionId) throws SQLException {
        return queryLongs("SELECT m.conversation_id FROM messages m "
                + "JOIN conversations c ON c.id = m.conversation_id WHERE c.session_id = ?", sessionId);
    }

    private List<Long> conversationIdsOfToolCalls(String sessionId) throws SQLException {
        return queryLongs("SELECT conversation_id FROM tool_calls WHERE session_id = ?", sessionId);
    }

    private List<String> messagesOf(String sessionId) throws SQLException {
        List<String> contents = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT m.content FROM messages m JOIN conversations c ON c.id = m.conversation_id "
                        + "WHERE c.session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    contents.add(rows.getString(1));
                }
            }
        }
        return contents;
    }

    /**
     * 用 {@code getObject(1, Long.class)} 而不是 {@code getLong(1)}：后者把 SQL NULL
     * 读成 {@code 0}，会让「conversation_id 为 NULL 的行」伪装成一条指向 id=0 的记录
     * ——正是本次写这个用例时踩到的坑（迁移其实是对的，是断言读错了）。
     */
    private List<Long> queryLongs(String sql, String parameter) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getObject(1, Long.class));
                }
            }
        }
        return values;
    }

    private int toolCallsWithNullConversation() throws SQLException {
        return countRows("tool_calls WHERE conversation_id IS NULL");
    }

    private int countRows(String tableAndWhere) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableAndWhere)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (ResultSet rows = connection.getMetaData()
                .getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rows.next();
        }
    }

    /** H2 的元数据里，唯一约束会以唯一索引的形式出现（NON_UNIQUE = false） */
    private boolean hasUniqueIndexOnSessionId() throws SQLException {
        try (ResultSet rows = connection.getMetaData()
                .getIndexInfo(null, null, "CONVERSATIONS", true, false)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if ("SESSION_ID".equalsIgnoreCase(column) && !rows.getBoolean("NON_UNIQUE")) {
                    return true;
                }
            }
        }
        return false;
    }
}
