package de.markusfisch.android.shadereditor.activity;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a compiled class refers to, read from its class file: the
 * classes in its constant pool, the fields and methods of other classes
 * it uses, and the types in its descriptors and generic signatures. A
 * name written out in full is in here as much as an imported one.
 */
public final class ClassReferences {
	/** A field or method of owner that the class uses. */
	public record Member(String owner, String name) {
	}

	private static final Pattern TYPE = Pattern.compile("L([^;<>]+)");

	// Binary names, as Class.forName() takes them.
	public final Set<String> classes = new TreeSet<>();
	public final Set<String> typesInSignatures = new TreeSet<>();
	public final List<Member> members = new ArrayList<>();

	private ClassReferences() {
	}

	public static ClassReferences read(InputStream in) throws IOException {
		var data = new DataInputStream(new BufferedInputStream(in));
		if (data.readInt() != 0xCAFEBABE) {
			throw new IOException("not a class file");
		}
		data.readUnsignedShort();
		data.readUnsignedShort();
		int count = data.readUnsignedShort();
		String[] utf8 = new String[count];
		int[] tags = new int[count];
		int[][] refs = new int[count][];
		for (int i = 1; i < count; ++i) {
			int tag = tags[i] = data.readUnsignedByte();
			switch (tag) {
				case 1 -> utf8[i] = data.readUTF();
				case 3, 4 -> data.readInt();
				case 5, 6 -> {
					data.readLong();
					// Takes two entries.
					++i;
				}
				case 7, 8, 16, 19, 20 -> refs[i] = new int[]{
						data.readUnsignedShort()};
				case 9, 10, 11, 12, 17, 18 -> refs[i] = new int[]{
						data.readUnsignedShort(), data.readUnsignedShort()};
				case 15 -> refs[i] = new int[]{
						data.readUnsignedByte(), data.readUnsignedShort()};
				default -> throw new IOException("constant tag " + tag);
			}
		}
		var references = new ClassReferences();
		for (int i = 1; i < count; ++i) {
			switch (tags[i]) {
				// Class: a name, or the descriptor of an array.
				case 7 -> {
					String name = utf8[refs[i][0]];
					if (name.startsWith("[")) {
						references.typesIn(name);
					} else {
						references.classes.add(binary(name));
					}
				}
				// Fieldref, Methodref, InterfaceMethodref.
				case 9, 10, 11 -> {
					int[] nameAndType = refs[refs[i][1]];
					references.members.add(new Member(
							binary(utf8[refs[refs[i][0]][0]]),
							utf8[nameAndType[0]]));
				}
				// NameAndType's descriptor, MethodType.
				case 12 -> references.typesIn(utf8[refs[i][1]]);
				case 16 -> references.typesIn(utf8[refs[i][0]]);
				default -> {
				}
			}
		}
		// Access, this, super, interfaces.
		data.readUnsignedShort();
		data.readUnsignedShort();
		data.readUnsignedShort();
		skip(data, 2 * data.readUnsignedShort());
		for (int table = 0; table < 2; ++table) {
			for (int n = data.readUnsignedShort(); n-- > 0; ) {
				data.readUnsignedShort();
				data.readUnsignedShort();
				references.typesIn(utf8[data.readUnsignedShort()]);
				references.readAttributes(data, utf8);
			}
		}
		references.readAttributes(data, utf8);
		return references;
	}

	private void readAttributes(DataInputStream data, String[] utf8)
			throws IOException {
		for (int n = data.readUnsignedShort(); n-- > 0; ) {
			String name = utf8[data.readUnsignedShort()];
			int length = data.readInt();
			if ("Signature".equals(name)) {
				typesIn(utf8[data.readUnsignedShort()]);
			} else {
				skip(data, length);
			}
		}
	}

	private static void skip(DataInputStream data, int length)
			throws IOException {
		data.readFully(new byte[length]);
	}

	private void typesIn(String descriptor) {
		Matcher matcher = TYPE.matcher(descriptor);
		while (matcher.find()) {
			typesInSignatures.add(binary(matcher.group(1)));
		}
	}

	private static String binary(String internalName) {
		return internalName.replace('/', '.');
	}
}
