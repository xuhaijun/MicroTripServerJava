package com.microtrip.server.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 轨迹记录实体（对应原 Node 版 trajectories 表）。
 * GPS 点与停留点以 JSON 文本存储（points_json / stops_json），简化关联。
 */
@Entity
@Table(name = "trajectories", indexes = {
        @Index(name = "idx_trajectories_user_start",
               columnList = "user_id, start_time DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trajectory {

    /** 轨迹 ID（App 端生成，幂等 upsert 的键） */
    @Id
    @Column(length = 64)
    private String id;

    /** 归属用户 ID（来自 JWT sub） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "start_time", nullable = false)
    private Long startTime;

    @Column(name = "end_time", nullable = false)
    private Long endTime;

    /** 总距离（米） */
    @Column(nullable = false)
    private Double distance;

    /** 总时长（秒） */
    @Column(nullable = false)
    private Long duration;

    @Column(name = "max_alt")
    private Double maxAlt;

    @Column(name = "min_alt")
    private Double minAlt;

    @Column(name = "ascent")
    private Double ascent;

    @Column(name = "descent")
    private Double descent;

    @Column(name = "avg_speed")
    private Double avgSpeed;

    @Column(length = 128)
    private String title;

    @Column(length = 512)
    private String note;

    @Column(length = 64)
    private String city;

    /** GPS 点数组 JSON（上限 20000 点） */
    @Column(name = "points_json", nullable = false, columnDefinition = "TEXT")
    private String pointsJson;

    /** 停留点数组 JSON（上限 500 条） */
    @Column(name = "stops_json", columnDefinition = "TEXT")
    private String stopsJson;

    /** 同步时间（毫秒时间戳） */
    @Column(name = "synced_at", nullable = false)
    private Long syncedAt;
}
