package ru.printlab.storage;

final class BoundsAccumulator {
    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double minZ = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;
    private double maxZ = Double.NEGATIVE_INFINITY;

    public void add(double x, double y, double z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    public ModelDimensions dimensions() {
        return new ModelDimensions(maxX - minX, maxY - minY, maxZ - minZ);
    }
}
