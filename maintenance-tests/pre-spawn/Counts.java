package audit;

public final class Counts {
    private static final ThreadLocal<long[]> ACTIVE = new ThreadLocal<>();

    public static void start() {
        ACTIVE.set(new long[3]);
    }

    public static void hit(int index) {
        long[] values = ACTIVE.get();
        if (values != null) {
            values[index]++;
        }
    }

    public static long[] stop() {
        long[] values = ACTIVE.get();
        ACTIVE.remove();
        return values;
    }
}
