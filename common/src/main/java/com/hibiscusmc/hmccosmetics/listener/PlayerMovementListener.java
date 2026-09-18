package com.hibiscusmc.hmccosmetics.listener;

import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.CosmeticUsers;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class PlayerMovementListener implements Listener {
    private static final List<CosmeticSlot> MOVEMENT_COSMETICS = List.of(
        CosmeticSlot.BACKPACK,
        CosmeticSlot.BALLOON
    );

    // Player Id -> Small Location
    private final Map<UUID, SmallLocation> locations = new HashMap<>();

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent ev) {
        final Player player = ev.getPlayer();

        final CosmeticUser user = CosmeticUsers.getUser(player);
        if(user == null) {
            return;
        }

        if(!updateDirtyLocation(ev.getPlayer(), ev.getTo())) {
            return;
        }

        for(final CosmeticSlot slot : MOVEMENT_COSMETICS) {
            user.updateMovementCosmetic(slot, ev.getFrom(), ev.getTo());
        }
    }

    /**
     * How far a yaw must move before the cosmetics are refreshed. mc-rpg probe: read from
     * `plugins/HMCCosmetics/gatetest.txt` so the trade-off between packet rate and how smoothly a worn
     * cosmetic follows a turn can be measured without a rebuild. Absent: the stock 5 degrees.
     */
    private static float yawGate() {
        try {
            java.io.File f = new java.io.File(
                    com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin.getInstance().getDataFolder(), "gatetest.txt");
            if (!f.isFile()) return 5f;
            String raw = java.nio.file.Files.readString(f.toPath()).trim();
            return raw.isEmpty() ? 5f : Float.parseFloat(raw);
        } catch (Throwable t) { return 5f; }
    }

    private boolean updateDirtyLocation(final Player player, final Location nextLoc) {
        final SmallLocation previous = locations.computeIfAbsent(
            player.getUniqueId(),
            $ -> SmallLocation.from(player, nextLoc)
        );
        final SmallLocation next = SmallLocation.from(player, nextLoc);

        if(next.distanceTo(previous) > 0.25) {
            this.locations.put(player.getUniqueId(), next);
            return true;
        }

        if(next.yawDistanceTo(previous) > yawGate()) {
            this.locations.put(player.getUniqueId(), next);
            return true;
        }

        // A worn backpack is oriented from the wearer's BODY yaw, which moves independently of the look
        // yaw: a player who turns their head leaves the body behind, and the body then catches up on its
        // own. Watching only the look yaw leaves the cosmetic at a stale angle for as long as the player
        // holds still afterwards.
        if(next.bodyYawDistanceTo(previous) > yawGate()) {
            this.locations.put(player.getUniqueId(), next);
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWorldChange(final PlayerChangedWorldEvent ev) {
        this.locations.remove(ev.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent ev) {
        locations.remove(ev.getPlayer().getUniqueId());
    }

    record SmallLocation(
        double x,
        double y,
        double z,
        float yaw,
        float bodyYaw
    ) {
        public double distanceTo(SmallLocation other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            double dz = this.z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public float yawDistanceTo(SmallLocation other) {
            return angleBetween(this.yaw, other.yaw);
        }

        public float bodyYawDistanceTo(SmallLocation other) {
            return angleBetween(this.bodyYaw, other.bodyYaw);
        }

        private static float angleBetween(float a, float b) {
            float diff = Math.abs(a - b) % 360;
            return diff > 180 ? 360 - diff : diff;
        }

        public static SmallLocation from(final Player player, final Location location) {
            return new SmallLocation(location.getX(), location.getY(), location.getZ(), location.getYaw(),
                player.getBodyYaw());
        }
    }
}
