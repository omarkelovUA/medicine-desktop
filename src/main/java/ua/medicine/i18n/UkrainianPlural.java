package ua.medicine.i18n;

public final class UkrainianPlural {

    private UkrainianPlural() {
    }

    /** "n word" with forms from a bundle key holding "one,few,many", e.g. plural.DAYS → "5 днів". */
    public static String count(long n, String formsKey) {
        String[] forms = Messages.get(formsKey).split(",");
        return n + " " + choose(n, forms[0], forms[1], forms[2]);
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
