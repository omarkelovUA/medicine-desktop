package ua.medicine.ui;

/** Where a bar's label goes on its track: inside, right of the bar, left of it, or cut inside. Pure. */
record LabelPlacement(double x, double width, boolean inside) {

    static LabelPlacement of(double barX, double barW, double textW, double trackW, double pad) {
        if (barW >= textW + 2 * pad) {
            return new LabelPlacement(barX + pad, textW, true);
        }
        if (barX + barW + pad + textW <= trackW) {
            return new LabelPlacement(barX + barW + pad, textW, false);
        }
        if (barX - pad - textW >= 0) {
            return new LabelPlacement(barX - pad - textW, textW, false);
        }
        // no room on either side: keep the bar's own (readable) style and let the text be cut
        return new LabelPlacement(barX + pad, Math.max(0, barW - 2 * pad), true);
    }
}
