package org.pinnaclesmp.fragguard;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.block.TileStateInventoryHolder;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Nameable;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class BlockEntitySnapshot {
    static final int FORMAT_VERSION = 1;
    private static final int MAGIC = 0x46474245;
    private static final int MAX_ITEM_BYTES = 16 * 1024 * 1024;
    private static final int MAX_COLLECTION_SIZE = 4_096;

    private BlockEntitySnapshot() {
    }

    static byte[] capture(Block block) {
        return block == null ? null : capture(block.getState());
    }

    static byte[] capture(BlockState state) {
        Kind kind = Kind.of(state);
        if (kind == null) {
            return null;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeUTF(kind.name());
            writeComponent(output, state instanceof Nameable nameable ? nameable.customName() : null);

            switch (kind) {
                case SIGN -> writeSign(output, (Sign) state);
                case BANNER -> writeBanner(output, (Banner) state);
                case SKULL -> writeSkull(output, (Skull) state);
                case LECTERN -> {
                    Lectern lectern = (Lectern) state;
                    writeInventory(output, lectern);
                    output.writeInt(lectern.getPage());
                }
                case DECORATED_POT -> {
                    DecoratedPot pot = (DecoratedPot) state;
                    writeInventory(output, pot);
                    output.writeInt(DecoratedPot.Side.values().length);
                    for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
                        output.writeUTF(side.name());
                        output.writeUTF(pot.getSherd(side).name());
                    }
                }
                case INVENTORY -> writeInventory(output, (TileStateInventoryHolder) state);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not serialize " + kind + " block-entity data", exception);
        }
        return bytes.toByteArray();
    }

    static void restore(Block block, byte[] payload) {
        if (payload == null) {
            return;
        }

        BlockState state = block.getState();
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid block-entity snapshot header");
            }
            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported block-entity snapshot format version " + version);
            }

            Kind kind = Kind.valueOf(input.readUTF());
            if (Kind.of(state) != kind) {
                throw new IOException("Snapshot type " + kind + " does not match " + state.getType());
            }

            Component customName = readComponent(input);
            if (state instanceof Nameable nameable) {
                nameable.customName(customName);
            }

            switch (kind) {
                case SIGN -> readSign(input, (Sign) state);
                case BANNER -> readBanner(input, (Banner) state);
                case SKULL -> readSkull(input, (Skull) state);
                case LECTERN -> {
                    Lectern lectern = (Lectern) state;
                    readInventory(input, lectern);
                    lectern.setPage(input.readInt());
                }
                case DECORATED_POT -> {
                    DecoratedPot pot = (DecoratedPot) state;
                    readInventory(input, pot);
                    int count = readCollectionSize(input, "decorated-pot sides");
                    for (int index = 0; index < count; index++) {
                        pot.setSherd(DecoratedPot.Side.valueOf(input.readUTF()), Material.valueOf(input.readUTF()));
                    }
                }
                case INVENTORY -> readInventory(input, (TileStateInventoryHolder) state);
            }

            if (!state.update(true, false)) {
                throw new IOException("The restored block-entity state was rejected");
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not restore block-entity data at " + block.getX() + ","
                    + block.getY() + "," + block.getZ(), exception);
        }
    }

    private static void writeSign(DataOutputStream output, Sign sign) throws IOException {
        output.writeBoolean(sign.isWaxed());
        for (Side side : new Side[]{Side.FRONT, Side.BACK}) {
            SignSide face = sign.getSide(side);
            output.writeUTF(face.getColor().name());
            output.writeBoolean(face.isGlowingText());
            List<Component> lines = face.lines();
            output.writeInt(lines.size());
            for (Component line : lines) {
                writeComponent(output, line);
            }
        }
    }

    private static void readSign(DataInputStream input, Sign sign) throws IOException {
        sign.setWaxed(input.readBoolean());
        for (Side side : new Side[]{Side.FRONT, Side.BACK}) {
            SignSide face = sign.getSide(side);
            face.setColor(DyeColor.valueOf(input.readUTF()));
            face.setGlowingText(input.readBoolean());
            int count = readCollectionSize(input, "sign lines");
            if (count > face.lines().size()) {
                throw new IOException("Snapshot contains more sign lines than this block supports");
            }
            for (int index = 0; index < count; index++) {
                face.line(index, Objects.requireNonNullElse(readComponent(input), Component.empty()));
            }
        }
    }

    private static void writeBanner(DataOutputStream output, Banner banner) throws IOException {
        Registry<PatternType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
        List<Pattern> patterns = banner.getPatterns();
        output.writeInt(patterns.size());
        for (Pattern pattern : patterns) {
            output.writeUTF(pattern.getColor().name());
            output.writeUTF(registry.getKeyOrThrow(pattern.getPattern()).toString());
        }
    }

    private static void readBanner(DataInputStream input, Banner banner) throws IOException {
        Registry<PatternType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
        int count = readCollectionSize(input, "banner patterns");
        List<Pattern> patterns = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            DyeColor color = DyeColor.valueOf(input.readUTF());
            NamespacedKey key = NamespacedKey.fromString(input.readUTF());
            if (key == null) {
                throw new IOException("Invalid banner pattern registry key");
            }
            patterns.add(new Pattern(color, registry.getOrThrow(key)));
        }
        banner.setPatterns(patterns);
    }

    private static void writeSkull(DataOutputStream output, Skull skull) throws IOException {
        ResolvableProfile profile = skull.getProfile();
        output.writeBoolean(profile != null);
        if (profile == null) {
            return;
        }
        writeNullableString(output, profile.uuid() == null ? null : profile.uuid().toString());
        writeNullableString(output, profile.name());
        List<ProfileProperty> properties = profile.properties().stream()
                .sorted(Comparator.comparing(ProfileProperty::getName)
                        .thenComparing(ProfileProperty::getValue)
                        .thenComparing(property -> Objects.toString(property.getSignature(), "")))
                .toList();
        output.writeInt(properties.size());
        for (ProfileProperty property : properties) {
            output.writeUTF(property.getName());
            output.writeUTF(property.getValue());
            writeNullableString(output, property.getSignature());
        }
    }

    private static void readSkull(DataInputStream input, Skull skull) throws IOException {
        if (!input.readBoolean()) {
            skull.setProfile(null);
            return;
        }
        String uuid = readNullableString(input);
        String name = readNullableString(input);
        ResolvableProfile.Builder builder = ResolvableProfile.resolvableProfile()
                .uuid(uuid == null ? null : UUID.fromString(uuid))
                .name(name);
        int count = readCollectionSize(input, "skull profile properties");
        for (int index = 0; index < count; index++) {
            builder.addProperty(new ProfileProperty(input.readUTF(), input.readUTF(), readNullableString(input)));
        }
        skull.setProfile(builder.build());
    }

    private static void writeInventory(DataOutputStream output, TileStateInventoryHolder holder) throws IOException {
        byte[] items = ItemStack.serializeItemsAsBytes(holder.getSnapshotInventory().getContents());
        output.writeInt(items.length);
        output.write(items);
    }

    private static void readInventory(DataInputStream input, TileStateInventoryHolder holder) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_ITEM_BYTES) {
            throw new IOException("Invalid serialized inventory length: " + length);
        }
        byte[] items = input.readNBytes(length);
        if (items.length != length) {
            throw new IOException("Incomplete serialized inventory");
        }
        holder.getSnapshotInventory().setContents(ItemStack.deserializeItemsFromBytes(items));
    }

    private static void writeComponent(DataOutputStream output, Component component) throws IOException {
        writeNullableString(output, component == null ? null : GsonComponentSerializer.gson().serialize(component));
    }

    private static Component readComponent(DataInputStream input) throws IOException {
        String value = readNullableString(input);
        return value == null ? null : GsonComponentSerializer.gson().deserialize(value);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }

    private static int readCollectionSize(DataInputStream input, String description) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    private enum Kind {
        SIGN,
        BANNER,
        SKULL,
        LECTERN,
        DECORATED_POT,
        INVENTORY;

        private static Kind of(BlockState state) {
            if (state instanceof Sign) {
                return SIGN;
            }
            if (state instanceof Banner) {
                return BANNER;
            }
            if (state instanceof Skull) {
                return SKULL;
            }
            if (state instanceof Lectern) {
                return LECTERN;
            }
            if (state instanceof DecoratedPot) {
                return DECORATED_POT;
            }
            return state instanceof TileStateInventoryHolder ? INVENTORY : null;
        }
    }
}
