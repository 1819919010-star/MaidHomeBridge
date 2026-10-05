package JumDa5he.maidhomebridge.server;

import com.google.gson.JsonObject;

/** Immutable transaction receipts and a separate current-residency record. */
public final class TransferPolicy {
    private TransferPolicy() {}
    public static boolean mayReceiveSource(JsonObject prior) {
        return prior==null||"REJECTED".equals(state(prior))||"OUTBOUND".equals(state(prior));
    }
    public static boolean mayReceiveTransaction(JsonObject prior) { return prior==null||"REJECTED".equals(state(prior)); }
    public static boolean mayExport(JsonObject prior) { return prior==null||"RESIDENT".equals(state(prior)); }
    private static String state(JsonObject j) {return j.has("state")?j.get("state").getAsString():"";}
}
