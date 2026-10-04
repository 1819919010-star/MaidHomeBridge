package JumDa5he.maidhomebridge.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Pure NBT comparison, independent of entity loading or the NeoForge lifecycle. */
final class SnapshotGuard {
    private SnapshotGuard() {}
    static boolean matches(CompoundTag previous, CompoundTag current, long elapsedTicks) {
        if (!sameEffects(previous.get("effects"), current.get("effects"), elapsedTicks)) return false;
        CompoundTag oldCopy = previous.copy(), newCopy = current.copy();
        oldCopy.remove("effects"); newCopy.remove("effects");
        return oldCopy.equals(newCopy);
    }
    private static boolean sameEffects(Tag before, Tag after, long elapsedTicks) {
        if (before == null || after == null) return before == after;
        if (before instanceof CompoundTag oldTag && after instanceof CompoundTag newTag) {
            if (!oldTag.getAllKeys().equals(newTag.getAllKeys())) return false;
            for (String key : oldTag.getAllKeys()) {
                Tag oldValue = oldTag.get(key), newValue = newTag.get(key);
                if ((key.equals("duration") || key.equals("Duration")) && oldValue instanceof NumericTag a && newValue instanceof NumericTag b) {
                    long oldDuration = a.getAsLong(), newDuration = b.getAsLong();
                    if (oldDuration < 0 ? newDuration != oldDuration : newDuration > oldDuration || newDuration < Math.max(0, oldDuration - elapsedTicks - 2)) return false;
                } else if (!sameEffects(oldValue, newValue, elapsedTicks)) return false;
            }
            return true;
        }
        if (before instanceof ListTag oldList && after instanceof ListTag newList) {
            if (oldList.size() != newList.size()) return false;
            for (int i = 0; i < oldList.size(); i++) if (!sameEffects(oldList.get(i), newList.get(i), elapsedTicks)) return false;
            return true;
        }
        return before.equals(after);
    }
}
