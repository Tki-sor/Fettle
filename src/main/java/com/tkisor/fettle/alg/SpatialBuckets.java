package com.tkisor.fettle.alg;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Coarse uniform-grid index over points, used to shrink the candidate set of the train collision
 * check from "every carriage of every train" to "carriages in the neighbouring cells".
 *
 * <p>The grid is deliberately conservative: a query visits every cell intersecting the query sphere's
 * bounding box and then filters by exact squared distance, so a candidate can never be missed. Cells
 * are cubic with the edge length of the query radius.
 */
public final class SpatialBuckets {

    private final double cell;
    private final Long2ObjectOpenHashMap<int[]> grid = new Long2ObjectOpenHashMap<>();
    private double[] xs = new double[0];
    private double[] ys = new double[0];
    private double[] zs = new double[0];
    private int size;
    private long visited;

    public SpatialBuckets(double cell) {
        this.cell = cell;
    }

    /** Number of points the grid has actually distance-tested; the deterministic work metric. */
    public long visited() {
        return visited;
    }

    public int size() {
        return size;
    }

    public void clear() {
        grid.clear();
        size = 0;
    }

    public void add(double x, double y, double z) {
        if (size == xs.length) {
            int grown = Math.max(16, size * 2);
            xs = java.util.Arrays.copyOf(xs, grown);
            ys = java.util.Arrays.copyOf(ys, grown);
            zs = java.util.Arrays.copyOf(zs, grown);
        }
        xs[size] = x;
        ys[size] = y;
        zs[size] = z;
        long key = key(x, y, z);
        int[] cellContents = grid.get(key);
        if (cellContents == null) {
            grid.put(key, new int[]{size});
        } else {
            int[] grown = java.util.Arrays.copyOf(cellContents, cellContents.length + 1);
            grown[cellContents.length] = size;
            grid.put(key, grown);
        }
        size++;
    }

    /** @return the number of points within {@code radius} of the query point. */
    public int countWithin(double x, double y, double z, double radius) {
        double radiusSqr = radius * radius;
        int minX = floorDiv(x - radius);
        int maxX = floorDiv(x + radius);
        int minY = floorDiv(y - radius);
        int maxY = floorDiv(y + radius);
        int minZ = floorDiv(z - radius);
        int maxZ = floorDiv(z + radius);
        int found = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    int[] contents = grid.get(key(cx, cy, cz));
                    if (contents == null) {
                        continue;
                    }
                    for (int index : contents) {
                        visited++;
                        double dx = xs[index] - x;
                        double dy = ys[index] - y;
                        double dz = zs[index] - z;
                        if (dx * dx + dy * dy + dz * dz <= radiusSqr) {
                            found++;
                        }
                    }
                }
            }
        }
        return found;
    }

    private long key(double x, double y, double z) {
        return key(floorDiv(x), floorDiv(y), floorDiv(z));
    }

    private long key(int cx, int cy, int cz) {
        return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0x1FFFFF) << 21) | (cz & 0x1FFFFF);
    }

    private int floorDiv(double value) {
        return (int) Math.floor(value / cell);
    }
}
