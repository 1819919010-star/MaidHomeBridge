package JumDa5he.maidhomebridge.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Pure NBT comparison, independent of entity loading or the NeoForge lifecycle. */
final class SnapshotGuard {
    private SnapshotGuard() {}
    private static CompoundTag comparable(CompoundTag input) {
        CompoundTag copy=input.copy();
        CompoundTag entity=copy.getCompound("data"), persistent=entity.getCompound("NeoForgeData");
        // Verified MoreAnimation playback bookkeeping, not relationship/progression data.
        // Deliberately no prefix-based exclusions and no blanket addon/attachment exclusion.
        for(String key:java.util.List.of("moreanimation_random_expression_next_check",
                "moreanimation_active_action", "moreanimation_active_start", "moreanimation_active_until",
                "moreanimation_active_priority", "moreanimation_active_lock_movement",
                "moreanimation_legacy_managed_action", "moreanimation_legacy_managed_start")) persistent.remove(key);
        if(persistent.isEmpty()) entity.remove("NeoForgeData");
        return copy;
    }
    /** Paths only: do not dump personal addon data or the entire maid archive to logs. */
    static String differences(CompoundTag previous, CompoundTag current, long elapsedTicks) {
        var result=new java.util.ArrayList<String>();
        CompoundTag a=comparable(previous), b=comparable(current);
        if(sameEffects(a.get("effects"), b.get("effects"), elapsedTicks)) {a.remove("effects");b.remove("effects");}
        differences(a,b,"",result);
        return String.join(", ",result);
    }
    private static void differences(Tag a, Tag b, String path, java.util.List<String> result) {
        if(result.size()>=8 || java.util.Objects.equals(a,b))return;
        if(a instanceof CompoundTag ac && b instanceof CompoundTag bc) {
            var keys=new java.util.TreeSet<>(ac.getAllKeys()); keys.addAll(bc.getAllKeys());
            for(String key:keys)differences(ac.get(key),bc.get(key),path.isEmpty()?key:path+"."+key,result);
        } else if(a instanceof ListTag al && b instanceof ListTag bl && al.size()==bl.size()) {
            for(int i=0;i<al.size();i++)differences(al.get(i),bl.get(i),path+"["+i+"]",result);
        } else result.add(path.substring(0,Math.min(path.length(),180)));
    }
    static boolean matches(CompoundTag previous, CompoundTag current, long elapsedTicks) {
        if (!sameEffects(previous.get("effects"), current.get("effects"), elapsedTicks)) return false;
        CompoundTag oldCopy = comparable(previous), newCopy = comparable(current);
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
