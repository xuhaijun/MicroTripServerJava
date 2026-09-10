package com.microtrip.server.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 轨迹 GPS 点（独立表，演进：将 {@code trajectories.points_json} 拆表）。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>{@code trajectories.points_json} 仍是<b>对外契约的规范存储</b>（App 端同步/详情直接读写），
 *       保证「Flutter 零改动」。</li>
 *   <li>本表是<b>查询优化副本</b>（CQRS 读侧）：对超长轨迹可按 {@code lat/lng/ts} 做 SQL 范围检索、
 *       密度统计、Geo 围栏，无需在应用层解析万级 JSON。同步时与 {@code points_json} 一起写入，删轨迹时级联清理。</li>
 * </ul>
 */
@Entity
@Table(name = "trajectory_points", indexes = {
        @Index(name = "idx_tp_traj_seq", columnList = "trajectory_id, seq"),
        @Index(name = "idx_tp_traj_latlng", columnList = "trajectory_id, lat, lng")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrajectoryPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属轨迹 ID（App 端生成，对应 trajectories.id） */
    @Column(name = "trajectory_id", nullable = false, length = 64)
    private String trajectoryId;

    /** 点在轨迹中的序号（0-based，保证顺序） */
    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 时间戳（毫秒） */
    @Column(nullable = false)
    private Long ts;

    @Column
    private Double alt;

    @Column
    private Double spd;

    @Column
    private Double acc;

    @Column
    private Double hdg;

    /** 其余未定义字段（预留扩展，JSON 文本） */
    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;
}
