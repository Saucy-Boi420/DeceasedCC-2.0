package net.deceasedcraft.deceasedcc.network;

import net.deceasedcraft.deceasedcc.items.TurretRemoteItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: rename a binding on the Turret Remote in the given hand. Server
 * validates the stack is a Turret Remote and the index is in range; the
 * new label is clamped to MAX_LABEL_LENGTH characters.
 *
 * The rename is purely cosmetic — only the binding's display label
 * changes; dimension/position are untouched.
 */
public class TurretRemoteRenamePacket {
    public static final int MAX_LABEL_LENGTH = 32;

    private final InteractionHand hand;
    private final int bindingIndex;
    private final String newLabel;

    public TurretRemoteRenamePacket(InteractionHand hand, int bindingIndex, String newLabel) {
        this.hand = hand;
        this.bindingIndex = bindingIndex;
        this.newLabel = newLabel;
    }

    public static void encode(TurretRemoteRenamePacket p, FriendlyByteBuf buf) {
        buf.writeEnum(p.hand);
        buf.writeVarInt(p.bindingIndex);
        buf.writeUtf(p.newLabel, MAX_LABEL_LENGTH);
    }

    public static TurretRemoteRenamePacket decode(FriendlyByteBuf buf) {
        return new TurretRemoteRenamePacket(
                buf.readEnum(InteractionHand.class),
                buf.readVarInt(),
                buf.readUtf(MAX_LABEL_LENGTH));
    }

    public static void handle(TurretRemoteRenamePacket p, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(p.hand);
            if (!(stack.getItem() instanceof TurretRemoteItem)) return;
            String clean = p.newLabel.length() > MAX_LABEL_LENGTH
                    ? p.newLabel.substring(0, MAX_LABEL_LENGTH)
                    : p.newLabel;
            TurretRemoteItem.setLabel(stack, p.bindingIndex, clean);
            // Nudge the inventory so the tooltip/GUI refreshes on clients.
            player.getInventory().setChanged();
        });
        ctx.setPacketHandled(true);
    }
}
