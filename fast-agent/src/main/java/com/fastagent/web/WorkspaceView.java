package com.fastagent.web;

import com.fastagent.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;

/**
 * 工作空间视图：登记信息 + 两个派生字段（数据目录、目录是否还在）。
 *
 * <p>之所以不直接把 {@link Workspace} 返回给前端：派生字段是算出来的，
 * 混进实体里会被一起写进登记表文件，索引就不干净了。
 */
public record WorkspaceView(
        String id,
        String name,
        String description,
        String ownerId,
        // 用户指定的目录
        String directory,
        // agent 数据目录：<directory>/.workspace
        String dataDir,
        // 用户目录是否还在（被手工移走时为 false，界面提示一下）
        boolean directoryExists,
        boolean dataDirExists,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static WorkspaceView of(Workspace w) {
        boolean dirOk = w.getDirectory() != null && Files.isDirectory(Paths.get(w.getDirectory()));
        boolean dataOk = w.getDirectory() != null && Files.isDirectory(w.dataDir());
        return new WorkspaceView(
                w.getId(),
                w.getName(),
                w.getDescription(),
                w.getOwnerId(),
                w.getDirectory(),
                String.valueOf(w.dataDir()),
                dirOk,
                dataOk,
                w.getCreatedAt(),
                w.getUpdatedAt());
    }
}
