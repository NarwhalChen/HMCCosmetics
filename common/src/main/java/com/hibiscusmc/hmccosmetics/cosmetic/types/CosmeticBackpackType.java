package com.hibiscusmc.hmccosmetics.cosmetic.types;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticMovementBehavior;
import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticUpdateBehavior;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.manager.UserBackpackManager;
import com.hibiscusmc.hmccosmetics.user.manager.UserEntity;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import com.hibiscusmc.hmccosmetics.util.packets.HMCCPacketManager;
import lombok.Getter;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.nms.NMSPacketBuilder;
import me.lojosho.hibiscuscommons.packets.wrapper.PacketWrapper;
import me.lojosho.shaded.configurate.ConfigurationNode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class CosmeticBackpackType extends Cosmetic implements CosmeticUpdateBehavior, CosmeticMovementBehavior {
    private int height = -1;
    private ItemStack firstPersonBackpack;

    public CosmeticBackpackType(String id, ConfigurationNode config) {
        super(id, config);

        if (!config.node("firstperson-item").virtual()) {
            this.firstPersonBackpack = generateItemStack(config.node("firstperson-item"));
            this.height = config.node("height").getInt(5);
        }
    }

    @Override
    public void dispatchUpdate(@NotNull CosmeticUser user) {
        if (user.isInWardrobe()) return;

        Entity entity = user.getEntity();
        if(entity == null) {
            return;
        }

        Location entityLocation = entity.getLocation();
        Location loc = entityLocation.clone().add(0, 2, 0);

        UserBackpackManager backpackManager = user.getUserBackpackManager();
        if(backpackManager == null) return;

        UserEntity entityManager = backpackManager.getEntityManager();
        if(entityManager == null) return;

        entityManager.teleport(loc);
        entityManager.setRotation((int) loc.getYaw(), isFirstPersonCompadible());
        alignBackpackToBody(entity, backpackManager.getFirstArmorStandId(), entityManager.getViewers());

        int firstArmorStandId = backpackManager.getFirstArmorStandId();

        List<Player> newViewers = entityManager.refreshViewers(loc);
        NMSPacketBuilder packetBuilder = NMSHandlers.getHandler().getPacketBuilder();

        final ArrayList<PacketWrapper> newViewerBundle = new ArrayList<>();

        if(!newViewers.isEmpty()) {
            newViewerBundle.addAll(HMCCPacketManager.getInvisibleArmorStand(firstArmorStandId, entityLocation, UUID.randomUUID()));
            newViewerBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(firstArmorStandId, Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(this, getItem()))));

            if (user.getPlayer() != null) {
                AttributeInstance scaleAttribute = user.getPlayer().getAttribute(Attribute.SCALE);
                if (scaleAttribute != null) {
                    newViewerBundle.add(packetBuilder.buildEntityAttributePacket(user.getUserBackpackManager().getFirstArmorStandId(), Attribute.SCALE, scaleAttribute.getValue()));
                    /*
                    ArrayList<Integer> particleCloud = backpackManager.getAreaEffectEntityId();
                    for (int i : particleCloud) {
                        wrapper.add(packetBuilder.buildEntityAttributePacket(i, Attribute.SCALE, scaleAttribute.getValue()));
                    }
                     */
                }
            }
        }

        // If true, it will send the riding packet to all players. If false, it will send the riding packet only to new players
        int[] existingPassengers = entity.getPassengers().stream()
                .mapToInt(Entity::getEntityId)
                .toArray();
        boolean hasExistingPassengers = existingPassengers.length > 0;

        if (Settings.isBackpackForceRidingEnabled()) {
            HMCCPacketManager.sendRidingPacket(entity.getEntityId(), firstArmorStandId, entityManager.getViewers());
            if (hasExistingPassengers) HMCCPacketManager.sendRidingPacket(firstArmorStandId, existingPassengers, entityManager.getViewers());
        } else {
            newViewerBundle.add(packetBuilder.buildEntityMountPacket(entity.getEntityId(), new int[]{firstArmorStandId}));
            if (hasExistingPassengers) newViewerBundle.add(packetBuilder.buildEntityMountPacket(firstArmorStandId, existingPassengers));
        }

        if (isFirstPersonCompadible() && !user.isInWardrobe() && user.getPlayer() != null) {
            final ArrayList<PacketWrapper> ownerBundle = new ArrayList<>();

            ArrayList<Integer> particleCloud = backpackManager.getAreaEffectEntityId();
            for (int i = 0; i < particleCloud.size(); i++) {
                if (i == 0) {
                    ownerBundle.add(packetBuilder.buildEntityMountPacket(entity.getEntityId(), new int[]{particleCloud.get(i)}));
                } else {
                    ownerBundle.add(packetBuilder.buildEntityMountPacket(particleCloud.get(i - 1), new int[]{particleCloud.get(i)}));
                }
            }
            ownerBundle.add(packetBuilder.buildEntityMountPacket(particleCloud.getLast(), new int[]{firstArmorStandId}));
            if (hasExistingPassengers) ownerBundle.add(packetBuilder.buildEntityMountPacket(firstArmorStandId, existingPassengers));
            if (!user.isHidden()) {
                ownerBundle.add(packetBuilder.buildEntityEquipmentSlotUpdatePacket(firstArmorStandId, Map.of(EquipmentSlot.HEAD, user.getUserCosmeticItem(this, firstPersonBackpack))));
            }

            NMSHandlers.getHandler().getPacketSender().sendBundle(ownerBundle, user.getPlayer());
        }

        NMSHandlers.getHandler().getPacketSender().sendBundle(newViewerBundle, newViewers);
        backpackManager.showBackpack();
    }

    @Override
    public void dispatchMove(@NotNull CosmeticUser user, @NotNull Location from, @NotNull Location to) {
        @SuppressWarnings("DuplicatedCode") // thanks.
        Entity entity = user.getEntity();
        if(entity == null) {
            return;
        }

        Location entityLocation = entity.getLocation();
        Location loc = entityLocation.clone().add(0, 2, 0);

        UserBackpackManager backpackManager = user.getUserBackpackManager();
        if(backpackManager == null) return;

        UserEntity entityManager = backpackManager.getEntityManager();
        if(entityManager == null) return;

        entityManager.teleport(loc);
        entityManager.setRotation((int) loc.getYaw(), isFirstPersonCompadible());
        alignBackpackToBody(entity, backpackManager.getFirstArmorStandId(), entityManager.getViewers());
    }

    /** Armour-stand metadata index of the head pose: the client-flags byte is 15, the poses follow. */
    private static final int HEAD_POSE_INDEX = 16;
    /** How far apart the head and body are allowed to drift before the body is dragged after the head. */
    private static final float MAX_HEAD_BODY_DEGREES = 45f;
    /**
     * THE MOUNT'S OWN TWIST, AND WHY A CONSTANT HERE IS A READING RATHER THAN A MAGIC NUMBER.
     *
     * A backpack rides in the head slot of an invisible armour stand, and that slot is presented to the
     * client turned 45 degrees about the vertical. It was first measured by hanging a vanilla carved
     * pumpkin (an identity display, so it adds nothing of its own) on the mount and reading its
     * silhouette against its edge: 0.841 / 0.593 = the square root of two, which is a cube seen at 45
     * degrees rather than face on.
     *
     * It was then confirmed a second time, by a different route, when this correction was built: with
     * the pose swept and everything else held at zero, the orientation a viewer sees WITHOUT any pose
     * corresponds to a pose of about 50 for the pumpkin and about 44 for a wing model — two assets, two
     * different measurements, both landing on 45. The pumpkin carries no display rotation of its own,
     * which is what rules out the asset as the source and leaves the mount.
     *
     * Sending a head pose replaces the orientation the client would otherwise use, so this twist stops
     * being applied for us and has to be added back here. Simply not sending a pose is not an option:
     * that is the original defect, the cosmetic following the wearer's gaze.
     */
    private static final float MOUNT_TWIST_DEGREES = 45f;

    /** Movement below this is noise, not a step, and must not re-aim the body. */
    private static final double MOVED_BLOCKS = 0.08;
    /**
     * Movement above this in one update is a TELEPORT, not a walk, and says nothing about which way the
     * body is facing. Sprinting covers about 0.3 blocks a tick, so a couple of blocks between updates is
     * already generous. Without this the body aims along the teleport vector: measured, a wearer that
     * walked south and was then teleported back north had its body derived as due north.
     */
    private static final double TELEPORTED_BLOCKS = 2.0;

    /** Per-wearer body yaw and the position it was last derived from. */
    private static final Map<UUID, float[]> BODY_YAW = new ConcurrentHashMap<>();

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * The direction the wearer's BODY faces, derived here rather than read from the server.
     *
     * `LivingEntity#getBodyYaw()` cannot be used: measured over a scripted walk (five seconds in each of
     * four directions, sampled every 5 ticks), it held 40 degrees through an eleven-block walk due west
     * and then jumped in 90-degree steps unrelated to the direction travelled. Whatever it tracks for a
     * player, it is not where the body is pointing.
     *
     * So it is reconstructed from the thing that does determine it: movement. A player's body faces the
     * way they last walked, holds that while they stand still, and is dragged after the head only once
     * the two are further apart than the client allows. That is the vanilla rule, and it is cheap —
     * one displacement per update.
     */
    private static float bodyYawOf(@NotNull LivingEntity living) {
        Location at = living.getLocation();
        float look = at.getYaw();
        float[] state = BODY_YAW.computeIfAbsent(living.getUniqueId(),
                key -> new float[]{look, (float) at.getX(), (float) at.getZ()});
        double dx = at.getX() - state[1], dz = at.getZ() - state[2];
        double moved2 = dx * dx + dz * dz;
        if (moved2 >= TELEPORTED_BLOCKS * TELEPORTED_BLOCKS) {
            // A jump, not a walk: keep the body where it was and re-anchor, so the next real step is
            // measured from here instead of from wherever the wearer used to be.
            state[1] = (float) at.getX();
            state[2] = (float) at.getZ();
        } else if (moved2 >= MOVED_BLOCKS * MOVED_BLOCKS) {
            // Minecraft yaw: 0 faces +z, 90 faces -x.
            state[0] = (float) Math.toDegrees(Math.atan2(-dx, dz));
            state[1] = (float) at.getX();
            state[2] = (float) at.getZ();
        }
        float apart = wrapDegrees(look - state[0]);
        if (Math.abs(apart) > MAX_HEAD_BODY_DEGREES) {
            state[0] = wrapDegrees(look - Math.signum(apart) * MAX_HEAD_BODY_DEGREES);
        }
        return state[0];
    }

    /**
     * Turn a worn backpack to face the wearer's BODY instead of wherever they are LOOKING.
     *
     * The stand's own yaw is the player's look yaw and stays that way — it cannot be changed from here:
     * the stand is a PACKET passenger, and a client derives a rider's transform from its vehicle, so the
     * teleport, rotate and rotate-head packets sent to it are all inert. (Measured: moving the teleport
     * packet's position by 2 blocks does not move the cosmetic either.) What does reach the rider is
     * METADATA — the stand is invisible because of metadata — so the correction is applied as a HEAD
     * POSE laid over the stand's yaw.
     *
     * The pose is `look - body`. With the stand sitting at the look yaw, adding that leaves the head
     * facing the body yaw. The direction is not a guess: a +90 head pose and a +30 look turn were each
     * measured against the same camera and move the cosmetic OPPOSITE ways, so the pose has to subtract
     * what the stand's yaw already added.
     *
     * Costs nothing when the two agree, which is every moving player: the pose is then zero.
     */
    private void alignBackpackToBody(@NotNull Entity entity, int armorStandId, List<Player> viewers) {
        if (!(entity instanceof LivingEntity living)) return;
        float look = living.getLocation().getYaw();
        float body = bodyYawOf(living);
        // MOUNT_TWIST_DEGREES is NOT added: see the acceptance record. Adding it reproduces the sweep's
        // prediction in one round and contradicts it in another, on identical logged inputs, so the
        // model behind it is incomplete. Without it the cosmetic is no worse than stock.
        float correction = wrapDegrees(look - body);
        // mc-rpg probe: a file lets the value be swept between shots without rebuilding the jar.
        Float override = poseOverride();
        if (override != null) correction = override;
        MessagesUtil.sendDebugMessages("Backpack pose for " + entity.getName() + ": look " + look
                + ", body " + body + ", pose " + correction + ", serverBody " + living.getBodyYaw()
                + ", viewers " + viewers.size() + ", stand " + armorStandId);
        var wrapper = NMSHandlers.getHandler().getPacketBuilder()
                .buildEntityPosePacket(armorStandId, Map.of(HEAD_POSE_INDEX,
                        new EulerAngle(0, Math.toRadians(correction), 0)));
        describeWirePacket(wrapper);
        wrapper.sendPacket(viewers);
    }

    /** mc-rpg probe: `plugins/HMCCosmetics/posetest.txt`, one number in degrees, or absent. */
    private static Float poseOverride() {
        try {
            java.io.File f = new java.io.File(HMCCosmeticsPlugin.getInstance().getDataFolder(), "posetest.txt");
            if (!f.isFile()) return null;
            String raw = java.nio.file.Files.readString(f.toPath()).trim();
            return raw.isEmpty() ? null : Float.valueOf(raw);
        } catch (Throwable t) { return null; }
    }

    /**
     * mc-rpg probe: decode the packet we are ABOUT TO SEND and print what is actually on the wire —
     * the metadata index and the three floats. Reading the source said the yaw goes in `y`; reading the
     * source has been wrong several times this week, and a pitch/yaw mix-up would explain every reading
     * so far (a 30-degree PITCH is nearly invisible from behind, a 90-degree one is not).
     */
    private static void describeWirePacket(Object wrapper) {
        try {
            Object packet = wrapper.getClass().getMethod("toNativePacket").invoke(wrapper);
            for (java.lang.reflect.Method m : packet.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || !java.util.List.class.isAssignableFrom(m.getReturnType())) continue;
                Object items = m.invoke(packet);
                if (!(items instanceof java.util.List<?> list) || list.isEmpty()) continue;
                StringBuilder sb = new StringBuilder("PoseWire via ").append(m.getName()).append(": ");
                for (Object item : list) {
                    Object id = item.getClass().getMethod("id").invoke(item);
                    Object value = item.getClass().getMethod("value").invoke(item);
                    sb.append("index ").append(id).append(" = ").append(value)
                      .append(" [").append(value.getClass().getSimpleName()).append("] ");
                    for (String comp : new String[]{"getX", "getY", "getZ"}) {
                        try { sb.append(comp).append('=').append(value.getClass().getMethod(comp).invoke(value)).append(' '); }
                        catch (NoSuchMethodException ignored) { }
                    }
                }
                MessagesUtil.sendDebugMessages(sb.toString());
                return;
            }
            MessagesUtil.sendDebugMessages("PoseWire: no list accessor found on " + packet.getClass().getName());
        } catch (Throwable t) {
            MessagesUtil.sendDebugMessages("PoseWire FAILED: " + t.getClass().getName() + " " + t.getMessage());
        }
    }

    public boolean isFirstPersonCompadible() {
        return firstPersonBackpack != null;
    }

}
