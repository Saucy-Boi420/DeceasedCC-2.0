package net.deceasedcraft.deceasedcc.items;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.media.IMedia;
import dan200.computercraft.api.media.MediaProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A ComputerCraft-compatible floppy disk packed with DeceasedCC Lua example
 * scripts. Insert into a CC Disk Drive placed next to a Computer; mounts at
 * {@code /disk/}. Users can browse with {@code ls /disk/}, view with
 * {@code edit /disk/foo.lua}, or copy with
 * {@code cp /disk/foo.lua foo.lua}.
 *
 * <p>Backed by a read-only resource mount over
 * {@code data/deceasedcc/lua_examples/} in the mod JAR, so updates ship
 * with the mod and the user can never accidentally corrupt the library.
 *
 * <p>Registered with CC's {@link ComputerCraftAPI#registerMediaProvider}
 * hook in {@link net.deceasedcraft.deceasedcc.DeceasedCC#commonSetup}.
 */
public class ExampleDiskItem extends Item {
    /** Resource sub-path under {@code data/<modid>/}. Must match the
     *  folder where the example scripts live on disk + in the JAR. */
    public static final String RESOURCE_PATH = "lua_examples";

    public ExampleDiskItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Insert into a CC Disk Drive — mounts as /disk/")
                .withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Run /disk/install to browse the examples")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Lambda-friendly {@link IMedia} impl shared by every disk stack. */
    public static final IMedia MEDIA = new IMedia() {
        @Override
        public String getLabel(ItemStack stack) {
            return stack.hasCustomHoverName()
                    ? stack.getHoverName().getString()
                    : "DeceasedCC Examples";
        }

        @Override
        public @Nullable Mount createDataMount(ItemStack stack, ServerLevel level) {
            MinecraftServer server = level.getServer();
            if (server == null) return null;
            return ComputerCraftAPI.createResourceMount(server,
                    net.deceasedcraft.deceasedcc.DeceasedCC.MODID,
                    RESOURCE_PATH);
        }
    };

    /** {@link MediaProvider} the mod registers with CC at common-setup time.
     *  Returns {@link #MEDIA} only for our disk; null for everything else. */
    public static final MediaProvider PROVIDER = stack ->
            stack.getItem() instanceof ExampleDiskItem ? MEDIA : null;
}
