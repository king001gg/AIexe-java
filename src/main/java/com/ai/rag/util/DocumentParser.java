package com.ai.rag.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析工具类
 */
@Slf4j
public class DocumentParser {

    /**
     * 解析文档内容
     */
    public static String parseDocument(File file) throws IOException {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            return parsePDF(file);
        } else if (fileName.endsWith(".doc")) {
            return parseWordDoc(file);
        } else if (fileName.endsWith(".docx")) {
            return parseWordDocx(file);
        } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            return parseExcel(file);
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + fileName);
        }
    }

    /**
     * 解析PDF文档
     */
    private static String parsePDF(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 解析Word文档（.doc）
     */
    private static String parseWordDoc(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             POIFSFileSystem fs = new POIFSFileSystem(fis);
             HWPFDocument document = new HWPFDocument(fs)) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText();
        }
    }

    /**
     * 解析Word文档（.docx）
     */
    private static String parseWordDocx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 解析Excel文档
     */
    private static String parseExcel(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
            StringBuilder content = new StringBuilder();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                content.append("Sheet ").append(i + 1).append(":\n");

                for (int j = 0; j <= sheet.getLastRowNum(); j++) {
                    if (sheet.getRow(j) != null) {
                        for (int k = 0; k < sheet.getRow(j).getLastCellNum(); k++) {
                            if (sheet.getRow(j).getCell(k) != null) {
                                content.append(sheet.getRow(j).getCell(k).getStringCellValue()).append("\t");
                            }
                        }
                        content.append("\n");
                    }
                }
                content.append("\n");
            }

            return content.toString();
        }
    }

    /**
     * 文档分块处理
     */
    public static List<String> chunkDocument(String content, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return chunks;
        }

        // 按段落分割
        String[] paragraphs = content.split("\\n\\s*\\n");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                continue;
            }

            // 如果段落本身就超过chunkSize，按句子分割
            if (paragraph.length() > chunkSize) {
                // 处理当前chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }

                // 分割长段落
                List<String> sentenceChunks = splitLongParagraph(paragraph, chunkSize, overlap);
                chunks.addAll(sentenceChunks);
            } else {
                // 添加到当前chunk
                if (currentChunk.length() + paragraph.length() + 1 > chunkSize) {
                    // 添加当前chunk
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(paragraph).append("\n");
            }
        }

        // 添加最后一个chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 分割长段落
     */
    private static List<String> splitLongParagraph(String paragraph, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        // 按句子分割
        String[] sentences = paragraph.split("[.!?。！？\\n]+");

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }

            if (currentChunk.length() + sentence.length() + 1 > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);

                    // 添加重叠部分
                    if (overlap > 0 && currentChunk.length() < overlap) {
                        String overlapText = getOverlapText(chunks.get(chunks.size() - 1), overlap);
                        currentChunk.append(overlapText);
                    }
                }
            }

            currentChunk.append(sentence).append(". ");
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 获取重叠文本
     */
    private static String getOverlapText(String text, int overlap) {
        if (text.length() <= overlap) {
            return text;
        }
        return text.substring(text.length() - overlap);
    }
}