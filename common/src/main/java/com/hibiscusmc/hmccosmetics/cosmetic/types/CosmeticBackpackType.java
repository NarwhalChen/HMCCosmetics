package com.hibiscusmc.hmccosmetics.cosmetic.types;

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
        float correction = living.getLocation().getYaw() - living.getBodyYaw();
        MessagesUtil.sendDebugMessages("Backpack pose for " + entity.getName() + ": look "
                + living.getLocation().getYaw() + ", body " + living.getBodyYaw() + ", pose " + correction);
        NMSHandlers.getHandler().getPacketBuilder()
                .buildEntityPosePacket(armorStandId, Map.of(HEAD_POSE_INDEX,
                        new EulerAngle(0, Math.toRadians(correction), 0)))
                .sendPacket(viewers);
    }

    public boolean isFirstPersonCompadible() {
        return firstPersonBackpack != null;
    }

}
