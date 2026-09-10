package com.microtrip.server.util;

/**
 * 球面距离计算（Haversine，地球半径 6371 km）。
 * 对应 Node 版 utils/haversine.js，用于景点周边推荐的距离排序。
 */
public final class Haversine {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private Haversine() {
    }

    /** 两点距离（km） */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /** 距离文案：<1km 显示「<n>m」，否则「<x.x>km」 */
    public static String formatDistance(double km) {
        if (km < 1) {
            return Math.round(km * 1000) + "m";
        }
        return String.format("%.1fkm", km);
    }
}
