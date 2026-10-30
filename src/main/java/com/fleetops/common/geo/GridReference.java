package com.fleetops.common.geo;

/**
 * Lettered map grid over the Punjab operating area, as printed on a survey sheet: columns A–M
 * west to east and rows 1–12 north to south, each square a quarter degree. Dispatchers use it to
 * say where something is ("TR-105 is in H7") without reading coordinates. The control tower map
 * draws the same grid, so the reference on a row and the square on the map always agree.
 */
public final class GridReference {

    public static final double WEST = 73.75;
    public static final double NORTH = 32.55;
    public static final double CELL = 0.25;
    public static final int COLUMNS = 13;
    public static final int ROWS = 12;

    private GridReference() {
    }

    public static String of(double lat, double lng) {
        int col = (int) Math.floor((lng - WEST) / CELL);
        int row = (int) Math.floor((NORTH - lat) / CELL);
        if (col < 0 || col >= COLUMNS || row < 0 || row >= ROWS) {
            return "—";
        }
        return String.valueOf((char) ('A' + col)) + (row + 1);
    }

    public static String of(GeoPoint p) {
        return p == null ? "—" : of(p.lat(), p.lng());
    }
}
