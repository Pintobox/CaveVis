package me.pintobox.cavevis.delivery;

import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.List;
import java.util.Set;

final class CaveVisPlayerNameTag {
    private static final int NORMAL_RANGE = 64;
    private static final int SNEAKING_RANGE = 32;

    // Text displays draw the text above their origin.
    private static final double LABEL_OFFSET_Y = 0.275;

    private final Display.TextDisplay display;
    private final TextDisplay settings;

    private Component name;
    private boolean seeThrough;

    private CaveVisPlayerNameTag(ServerPlayer target, int updateInterval) {
        display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, target.level());
        settings = (TextDisplay) display.getBukkitEntity();

        settings.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        settings.setDefaultBackground(true);
        settings.setLineWidth(Integer.MAX_VALUE);
        settings.setTeleportDuration(Math.clamp(updateInterval, 1, 59));
    }

    static CaveVisPlayerNameTag update(
        Player viewer,
        Player target,
        CaveVisPlayerNameTag current,
        int updateInterval
    ) {
        ServerPlayer observer = ((CraftPlayer) viewer).getHandle();
        ServerPlayer subject = ((CraftPlayer) target).getHandle();

        boolean sneaking = target.isSneaking();
        int range = sneaking ? SNEAKING_RANGE : NORMAL_RANGE;

        if (!viewer.canSee(target)
            || observer.getCamera().position().distanceToSqr(subject.position())
            >= range * range) {
            remove(viewer, current);
            return null;
        }

        var scoreboard = ((CraftScoreboard) viewer.getScoreboard()).getHandle();
        PlayerTeam team = scoreboard.getPlayersTeam(subject.getScoreboardName());
        PlayerTeam viewerTeam = scoreboard.getPlayersTeam(observer.getScoreboardName());

        boolean sameTeam = team != null && team == viewerTeam;
        if (team != null) {
            boolean permitted = switch (team.getNameTagVisibility()) {
                case ALWAYS -> true;
                case NEVER -> false;
                case HIDE_FOR_OTHER_TEAMS -> sameTeam;
                case HIDE_FOR_OWN_TEAM -> !sameTeam;
            };

            if (!permitted) {
                remove(viewer, current);
                return null;
            }
        }

        if (target.isInvisible() && !(sameTeam && team.canSeeFriendlyInvisibles())) {
            remove(viewer, current);
            return null;
        }

        Vec3 attachment = subject.getAttachments().getNullable(
            EntityAttachment.NAME_TAG, 0, subject.getYRot()
        );
        if (attachment == null) {
            remove(viewer, current);
            return null;
        }

        Vec3 position = subject.position().add(attachment).add(0, LABEL_OFFSET_Y, 0);
        Component name = PlayerTeam.formatNameForTeam(team, subject.getName());

        if (current == null) {
            current = new CaveVisPlayerNameTag(subject, updateInterval);
            current.updateText(name, !sneaking);
            current.display.setPos(position.x, position.y, position.z);

            int id = current.display.getId();
            observer.connection.send(new ClientboundBundlePacket(List.of(
                new ClientboundAddEntityPacket(
                    id, current.display.getUUID(),
                    position.x, position.y, position.z,
                    0.0f, 0.0f, EntityType.TEXT_DISPLAY, 0, Vec3.ZERO, 0.0
                ),
                new ClientboundSetEntityDataPacket(
                    id, current.display.getEntityData().packDirty()
                )
            )));
            return current;
        }

        current.updateText(name, !sneaking);
        var changes = current.display.getEntityData().packDirty();
        if (changes != null) {
            observer.connection.send(
                new ClientboundSetEntityDataPacket(current.display.getId(), changes)
            );
        }

        if (!current.display.position().equals(position)) {
            current.display.setPos(position.x, position.y, position.z);
            observer.connection.send(new ClientboundTeleportEntityPacket(
                current.display.getId(),
                new PositionMoveRotation(position, Vec3.ZERO, 0.0f, 0.0f),
                Set.of(),
                false
            ));
        }

        return current;
    }

    private void updateText(Component name, boolean seeThrough) {
        if (!name.equals(this.name)) {
            settings.text(PaperAdventure.asAdventure(name));
            this.name = name;
        }

        if (this.seeThrough != seeThrough) {
            settings.setSeeThrough(seeThrough);
            this.seeThrough = seeThrough;
        }
    }

    static void remove(Player viewer, CaveVisPlayerNameTag tag) {
        if (tag != null) {
            ((CraftPlayer) viewer).getHandle().connection.send(
                new ClientboundRemoveEntitiesPacket(tag.display.getId())
            );
        }
    }
}