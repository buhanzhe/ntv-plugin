package xiao.bu.channel.files.compat;

/** Java 7 helpers used by jcifs-ng, implemented with API-14 classes. */
public final class Objects {
    private Objects() {}
    public static boolean equals(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    public static int hashCode(Object value) { return value == null ? 0 : value.hashCode(); }
    public static int hash(Object... values) { return java.util.Arrays.hashCode(values); }
}
