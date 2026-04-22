package net.deceasedcraft.deceasedcc.items;

import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity.DeviceType;
import net.deceasedcraft.deceasedcc.blocks.entity.AdvancedNetworkControllerBlockEntity.LinkedDevice;
import net.deceasedcraft.deceasedcc.blocks.entity.CameraBlockEntity;
import net.deceasedcraft.deceasedcc.blocks.entity.HologramProjectorBlockEntity;
import net.deceasedcraft.deceasedcc.core.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Universal linker. LINK mode (default): click device → click controller
 * OR click camera/projector → click its counterpart (camera↔projector
 * peer pair, Phase 8). CHAIN mode: click linked source → click target;
 * target joins source's controller; clicking an already-linked device
 * during chaining removes it. Shift+right-click toggles mode. Popups
 * use CC-style {@code type_N} names.
 */
public class LinkingToolItem extends Item {
    private static final String NBT_POS  = "storedDevicePos";
    private static final String NBT_TYPE = "storedDeviceType";
    private static final String NBT_MODE = "linkMode";
    private static final String NBT_CHAIN_SRC = "chainSourcePos";
    private static final String MODE_LINK  = "LINK";
    private static final String MODE_CHAIN = "CHAIN";

    public LinkingToolItem(Properties props) {
        super(props);
    }

    // ---- mode state --------------------------------------------------

    private static String getMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return MODE_LINK;
        String m = tag.getString(NBT_MODE);
        return MODE_CHAIN.equals(m) ? MODE_CHAIN : MODE_LINK;
    }

    private static void setMode(ItemStack stack, String mode) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(NBT_MODE, mode);
        if (MODE_LINK.equals(mode)) tag.remove(NBT_CHAIN_SRC);
        else { tag.remove(NBT_POS); tag.remove(NBT_TYPE); }
    }

    /** Shift+right-click on air (or any block we PASS on) toggles mode. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        String next = MODE_LINK.equals(getMode(stack)) ? MODE_CHAIN : MODE_LINK;
        setMode(stack, next);
        tell(player, "Mode: " + next, MODE_CHAIN.equals(next) ? ChatFormatting.GOLD : ChatFormatting.AQUA);
        return InteractionResultHolder.success(stack);
    }

    // ---- use-on-block ------------------------------------------------

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) return InteractionResult.PASS; // let use() toggle
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ItemStack stack = ctx.getItemInHand();
        if (MODE_CHAIN.equals(getMode(stack))) return useOnChain(ctx, stack, player, level);
        return useOnLink(ctx, stack, player, level);
    }

    // ---- LINK mode ---------------------------------------------------

    private InteractionResult useOnLink(UseOnContext ctx, ItemStack stack, Player player, Level level) {
        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);

        if (be instanceof AdvancedNetworkControllerBlockEntity controller) {
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(NBT_POS) || !tag.contains(NBT_TYPE)) {
                tell(player, "Click a device first.", ChatFormatting.YELLOW);
                return InteractionResult.CONSUME;
            }
            BlockPos devicePos = BlockPos.of(tag.getLong(NBT_POS));
            DeviceType type;
            try { type = DeviceType.valueOf(tag.getString(NBT_TYPE)); }
            catch (IllegalArgumentException bad) {
                tell(player, "Invalid device. Re-store.", ChatFormatting.RED);
                tag.remove(NBT_POS); tag.remove(NBT_TYPE);
                return InteractionResult.CONSUME;
            }
            int cap = ModConfig.ADV_NETWORK_MAX_CONNECTIONS.get();
            int result = controller.linkDevice(devicePos, type, cap);
            if (result == -1) {
                tell(player, "Already linked.", ChatFormatting.YELLOW);
            } else if (result == -2) {
                tell(player, "Controller full (" + cap + "/" + cap + ").", ChatFormatting.RED);
            } else {
                String name = controller.getDeviceName(result);
                tell(player, "Linked " + name + " (" + controller.deviceCount() + "/" + cap + ")", ChatFormatting.GREEN);
                tag.remove(NBT_POS); tag.remove(NBT_TYPE);
            }
            return InteractionResult.CONSUME;
        }

        DeviceType detected = AdvancedNetworkControllerBlockEntity.detectType(be);
        if (detected != null) {
            // Phase 8 — camera ↔ projector peer pair. If the tool already
            // holds a stored device AND the stored+clicked form a
            // camera/projector pair, write the bidirectional pair directly
            // (no controller involved). Shift+right-click still toggles
            // mode; controller-link path still fires when a controller
            // is clicked.
            CompoundTag existing = stack.getTag();
            if (existing != null && existing.contains(NBT_POS) && existing.contains(NBT_TYPE)) {
                DeviceType storedType;
                try { storedType = DeviceType.valueOf(existing.getString(NBT_TYPE)); }
                catch (IllegalArgumentException bad) { storedType = null; }
                BlockPos storedPos = BlockPos.of(existing.getLong(NBT_POS));
                if (storedType != null && isCamProjPair(storedType, detected) && !storedPos.equals(pos)) {
                    if (writeCamProjPair(level, storedPos, storedType, pos, detected, player)) {
                        existing.remove(NBT_POS);
                        existing.remove(NBT_TYPE);
                    }
                    return InteractionResult.CONSUME;
                }
            }

            CompoundTag tag = stack.getOrCreateTag();
            tag.putLong(NBT_POS, pos.asLong());
            tag.putString(NBT_TYPE, detected.name());
            String next = (detected == DeviceType.CAMERA || detected == DeviceType.HOLOGRAM_PROJECTOR)
                    ? ". Click a controller or counterpart to pair."
                    : ". Click a controller.";
            tell(player, "Stored " + detected.luaName() + next, ChatFormatting.AQUA);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    // ---- camera ↔ projector peer pairing (Phase 8) -------------------

    private static boolean isCamProjPair(DeviceType a, DeviceType b) {
        return (a == DeviceType.CAMERA && b == DeviceType.HOLOGRAM_PROJECTOR)
            || (a == DeviceType.HOLOGRAM_PROJECTOR && b == DeviceType.CAMERA);
    }

    /** Write a bidirectional camera↔projector pair, severing any prior
     *  pairings on either side first so no dangling fields survive.
     *  Returns true on success, false if either BE is unloaded / wrong type. */
    private static boolean writeCamProjPair(Level level,
                                             BlockPos aPos, DeviceType aType,
                                             BlockPos bPos, DeviceType bType,
                                             Player player) {
        BlockPos camPos  = (aType == DeviceType.CAMERA) ? aPos : bPos;
        BlockPos projPos = (aType == DeviceType.HOLOGRAM_PROJECTOR) ? aPos : bPos;
        BlockEntity camBE  = level.getBlockEntity(camPos);
        BlockEntity projBE = level.getBlockEntity(projPos);
        if (!(camBE instanceof CameraBlockEntity cam) || !(projBE instanceof HologramProjectorBlockEntity proj)) {
            tell(player, "Pair target not loaded.", ChatFormatting.RED);
            return false;
        }

        // Sever prior counterparts (if any) so nothing dangles.
        BlockPos oldProj = cam.getPairedProjector();
        if (oldProj != null && !oldProj.equals(projPos)) {
            BlockEntity oldBE = level.getBlockEntity(oldProj);
            if (oldBE instanceof HologramProjectorBlockEntity oldP) oldP.clearPairedCamera();
        }
        BlockPos oldCam = proj.getPairedCamera();
        if (oldCam != null && !oldCam.equals(camPos)) {
            BlockEntity oldBE = level.getBlockEntity(oldCam);
            if (oldBE instanceof CameraBlockEntity oldC) oldC.clearPairedProjector();
        }

        cam.setPairedProjector(projPos);
        proj.setPairedCamera(camPos);
        tell(player, "Paired camera ↔ projector", ChatFormatting.GREEN);
        return true;
    }

    // ---- CHAIN mode --------------------------------------------------

    private InteractionResult useOnChain(UseOnContext ctx, ItemStack stack, Player player, Level level) {
        BlockPos clicked = ctx.getClickedPos().immutable();
        BlockEntity be = level.getBlockEntity(clicked);

        if (be instanceof AdvancedNetworkControllerBlockEntity) {
            tell(player, "CHAIN: click devices, not controllers.", ChatFormatting.YELLOW);
            return InteractionResult.CONSUME;
        }
        DeviceType detected = AdvancedNetworkControllerBlockEntity.detectType(be);
        if (detected == null) return InteractionResult.PASS;

        CompoundTag tag = stack.getOrCreateTag();

        // First click — set chain source.
        if (!tag.contains(NBT_CHAIN_SRC)) {
            BlockPos srcCtrlPos = AdvancedNetworkControllerBlockEntity.findControllerForDevice(clicked);
            if (srcCtrlPos == null) {
                tell(player, "Source must be linked (use LINK mode first).", ChatFormatting.RED);
                return InteractionResult.CONSUME;
            }
            String srcName = nameOf(level, srcCtrlPos, clicked);
            tag.putLong(NBT_CHAIN_SRC, clicked.asLong());
            tell(player, "Source: " + (srcName != null ? srcName : detected.luaName()), ChatFormatting.AQUA);
            return InteractionResult.CONSUME;
        }

        // Second+ click — chain target.
        BlockPos sourcePos = BlockPos.of(tag.getLong(NBT_CHAIN_SRC));
        if (clicked.equals(sourcePos)) {
            tell(player, "Same device. Pick another.", ChatFormatting.YELLOW);
            return InteractionResult.CONSUME;
        }

        BlockPos srcCtrlPos = AdvancedNetworkControllerBlockEntity.findControllerForDevice(sourcePos);
        if (srcCtrlPos == null) {
            tag.remove(NBT_CHAIN_SRC);
            tell(player, "Source unlinked. Chain reset.", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        BlockEntity ctrlBE = level.getBlockEntity(srcCtrlPos);
        if (!(ctrlBE instanceof AdvancedNetworkControllerBlockEntity ctrl)) {
            tag.remove(NBT_CHAIN_SRC);
            tell(player, "Source controller missing.", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }

        BlockPos targetCurrentCtrl = AdvancedNetworkControllerBlockEntity.findControllerForDevice(clicked);
        if (targetCurrentCtrl != null) {
            BlockEntity oldCtrlBE = level.getBlockEntity(targetCurrentCtrl);
            String removedName = detected.luaName();
            if (oldCtrlBE instanceof AdvancedNetworkControllerBlockEntity oldCtrl) {
                int removedId = -1;
                List<LinkedDevice> list = oldCtrl.linkedDevices();
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).pos().equals(clicked)) { removedId = i + 1; break; }
                }
                if (removedId > 0) {
                    String n = oldCtrl.getDeviceName(removedId);
                    if (n != null) removedName = n;
                    oldCtrl.unlinkDeviceById(removedId);
                }
            }
            tag.remove(NBT_CHAIN_SRC);
            tell(player, "Unlinked " + removedName + ". Chain ended.", ChatFormatting.GOLD);
            return InteractionResult.CONSUME;
        }

        int cap = ModConfig.ADV_NETWORK_MAX_CONNECTIONS.get();
        int result = ctrl.linkDevice(clicked, detected, cap);
        if (result == -2) {
            tell(player, "Controller full. Chain ended.", ChatFormatting.RED);
            tag.remove(NBT_CHAIN_SRC);
        } else if (result == -1) {
            tell(player, "Already linked here. Chain advanced.", ChatFormatting.YELLOW);
            tag.putLong(NBT_CHAIN_SRC, clicked.asLong());
        } else {
            String name = ctrl.getDeviceName(result);
            tag.putLong(NBT_CHAIN_SRC, clicked.asLong());
            tell(player, "Chained " + name + " (" + ctrl.deviceCount() + "/" + cap + ")", ChatFormatting.GREEN);
        }
        return InteractionResult.CONSUME;
    }

    // ---- helpers -----------------------------------------------------

    @Nullable
    private static String nameOf(Level level, BlockPos ctrlPos, BlockPos devicePos) {
        BlockEntity be = level.getBlockEntity(ctrlPos);
        if (!(be instanceof AdvancedNetworkControllerBlockEntity ctrl)) return null;
        List<LinkedDevice> list = ctrl.linkedDevices();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pos().equals(devicePos)) return ctrl.getDeviceName(i + 1);
        }
        return null;
    }

    private static void tell(Player p, String msg, ChatFormatting color) {
        p.displayClientMessage(Component.literal(msg).withStyle(color), true);
    }

    // ---- tooltip -----------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                 List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        String mode = getMode(stack);
        tooltip.add(Component.literal("Mode: " + mode)
                .withStyle(MODE_CHAIN.equals(mode) ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        tooltip.add(Component.literal("Shift+right-click: toggle mode")
                .withStyle(ChatFormatting.DARK_GRAY));
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        if (MODE_LINK.equals(mode) && tag.contains(NBT_POS) && tag.contains(NBT_TYPE)) {
            String storedType = tag.getString(NBT_TYPE).toLowerCase();
            tooltip.add(Component.literal("Stored: " + storedType)
                    .withStyle(ChatFormatting.GRAY));
            if (storedType.equals("camera") || storedType.equals("hologram_projector")) {
                tooltip.add(Component.literal("Click controller OR counterpart to pair")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (MODE_CHAIN.equals(mode) && tag.contains(NBT_CHAIN_SRC)) {
            BlockPos p = BlockPos.of(tag.getLong(NBT_CHAIN_SRC));
            tooltip.add(Component.literal("Source @ " + p.getX() + "," + p.getY() + "," + p.getZ())
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
