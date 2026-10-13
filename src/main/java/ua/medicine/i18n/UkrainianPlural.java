package ua.medicine.i18n;

public final class UkrainianPlural {

    private UkrainianPlural() {
    }

    /** Picks the Ukrainian plural form: 1 день, 2 дні, 5 днів. */
    public static String choose(long n, String one, String few, String many) {
        long mod10 = n % 10;
        long mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }
}
