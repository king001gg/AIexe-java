package com.ai.rag.model.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 文档上传请求DTO
 */
@Data
public class DocumentUploadRequest {

    @NotNull(message = "文件不能为空")
    private MultipartFile file;

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 255, message = "知识库名称不能超过255字符")
    private String knowledgeBaseName;

    @Size(max = 500, message = "描述不能超过500字符")
    private String description;

    @Size(max = 100, message = "创建者不能超过100字符")
    private String createdBy;

    private Integer chunkSize = 1000;
    private Integer chunkOverlap = 200;
}