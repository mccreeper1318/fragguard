from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Missing expected text for {label}")
    if text.count(old) != 1:
        raise SystemExit(f"Expected one occurrence for {label}, found {text.count(old)}")
    return text.replace(old, new, 1)


path = Path("src/main/java/org/pinnaclesmp/fragguard/BlockEntitySnapshot.java")
text = path.read_text()
text = replace_once(
    text,
    "import java.util.ArrayList;\nimport java.util.Collection;\n",
    "import java.util.ArrayList;\nimport java.util.Arrays;\nimport java.util.Collection;\n",
    "BlockEntitySnapshot Arrays import",
)
marker = "    static void restore(Block block, byte[] payload) {\n"
compatibility = r'''    static boolean equivalent(byte[] first, byte[] second) {
        if (Arrays.equals(first, second)) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        try {
            return Arrays.equals(canonicalize(first), canonicalize(second));
        } catch (IOException | RuntimeException exception) {
            // Historical state that cannot be proven equivalent must remain a conflict.
            return false;
        }
    }

    private static byte[] canonicalize(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid block-entity snapshot header");
            }
            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported block-entity snapshot format version " + version);
            }

            Kind kind = Kind.valueOf(input.readUTF());
            output.writeInt(MAGIC);
            output.writeByte(version);
            output.writeUTF(kind.name());
            writeComponent(output, readComponent(input));

            switch (kind) {
                case SIGN -> canonicalizeSign(input, output);
                case BANNER -> canonicalizeBanner(input, output);
                case SKULL -> canonicalizeSkull(input, output);
                case LECTERN -> {
                    canonicalizeInventory(input, output);
                    output.writeInt(input.readInt());
                }
                case DECORATED_POT -> {
                    canonicalizeInventory(input, output);
                    int count = readCollectionSize(input, "decorated-pot sides");
                    writeCollectionSize(output, count, "decorated-pot sides");
                    for (int index = 0; index < count; index++) {
                        output.writeUTF(input.readUTF());
                        output.writeUTF(input.readUTF());
                    }
                }
                case INVENTORY -> canonicalizeInventory(input, output);
            }

            if (input.read() != -1) {
                throw new IOException("Unexpected trailing block-entity snapshot data");
            }
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static void canonicalizeSign(DataInputStream input, DataOutputStream output) throws IOException {
        output.writeBoolean(input.readBoolean());
        for (int side = 0; side < 2; side++) {
            output.writeUTF(input.readUTF());
            output.writeBoolean(input.readBoolean());
            int count = readCollectionSize(input, "sign lines");
            writeCollectionSize(output, count, "sign lines");
            for (int index = 0; index < count; index++) {
                writeComponent(output, readComponent(input));
            }
        }
    }

    private static void canonicalizeBanner(DataInputStream input, DataOutputStream output) throws IOException {
        int count = readCollectionSize(input, "banner patterns");
        writeCollectionSize(output, count, "banner patterns");
        for (int index = 0; index < count; index++) {
            output.writeUTF(input.readUTF());
            output.writeUTF(input.readUTF());
        }
    }

    private static void canonicalizeSkull(DataInputStream input, DataOutputStream output) throws IOException {
        boolean present = input.readBoolean();
        output.writeBoolean(present);
        if (!present) {
            return;
        }
        writeNullableString(output, readNullableString(input));
        writeNullableString(output, readNullableString(input));
        int count = readCollectionSize(input, "skull profile properties");
        writeCollectionSize(output, count, "skull profile properties");
        for (int index = 0; index < count; index++) {
            output.writeUTF(input.readUTF());
            output.writeUTF(input.readUTF());
            writeNullableString(output, readNullableString(input));
        }
    }

    private static void canonicalizeInventory(DataInputStream input, DataOutputStream output) throws IOException {
        int length = input.readInt();
        validateInventoryLength(length);
        byte[] storedItems = input.readNBytes(length);
        if (storedItems.length != length) {
            throw new IOException("Incomplete serialized inventory");
        }

        ItemStack[] contents = ItemStack.deserializeItemsFromBytes(storedItems);
        byte[] currentItems = ItemStack.serializeItemsAsBytes(contents);
        validateInventoryLength(currentItems.length);
        output.writeInt(currentItems.length);
        output.write(currentItems);
    }

'''
text = replace_once(text, marker, compatibility + marker, "BlockEntitySnapshot compatibility methods")
path.write_text(text)

path = Path("src/main/java/org/pinnaclesmp/fragguard/FragGuardCommand.java")
text = path.read_text()
old = '''        return actualData.equals(expectedData)
                && !Arrays.equals(expectedEntityData, UNKNOWN_ENTITY_STATE)
                && (expectedEntityData == null
                ? actualEntityData == null
                : Arrays.equals(actualEntityData, expectedEntityData));'''
new = '''        return actualData.equals(expectedData)
                && !Arrays.equals(expectedEntityData, UNKNOWN_ENTITY_STATE)
                && (expectedEntityData == null
                ? actualEntityData == null
                : actualEntityData != null && BlockEntitySnapshot.equivalent(actualEntityData, expectedEntityData));'''
text = replace_once(text, old, new, "rollback block-entity comparison")
path.write_text(text)

path = Path("src/test/java/org/pinnaclesmp/fragguard/Paper262PersistenceCompatibilityTest.java")
text = path.read_text()
marker = "    @Test\n    void preservesExactPaper26_2ItemBytesInContainerLecternAndPotEnvelopes() throws Exception {\n"
test = '''    @Test
    void comparesEquivalentFormatOneSnapshotsAfterTransportEncodingChanges() throws Exception {
        byte[] original = signSnapshot();
        byte[] sameContentsDifferentGzipHeader = original.clone();
        sameContentsDifferentGzipHeader[4] = 1;

        assertFalse(java.util.Arrays.equals(original, sameContentsDifferentGzipHeader));
        assertTrue(BlockEntitySnapshot.equivalent(original, sameContentsDifferentGzipHeader),
                "format-one comparison should use decoded persistent state rather than transport bytes");
        assertFalse(BlockEntitySnapshot.equivalent(original, new byte[]{1, 2, 3}),
                "undecodable history must fail closed");
    }

'''
text = replace_once(text, marker, test + marker, "compatibility comparator test")
path.write_text(text)

path = Path("changelog.md")
text = path.read_text()
marker = "### Fixed\n\n"
line = "- Fixed #80 cross-version rollback conflicts by comparing compatible format-v1 block-entity snapshots after in-memory normalization through the current Paper item serializer; Paper 26.2 history, container contents, rollback snapshots, and undo data remain stored byte-for-byte and require no schema rewrite.\n"
if line not in text:
    text = replace_once(text, marker, marker + line, "26.3 Fixed changelog section")
    path.write_text(text)
