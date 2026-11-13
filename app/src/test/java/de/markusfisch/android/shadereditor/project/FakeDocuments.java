package de.markusfisch.android.shadereditor.project;

import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A documents provider in memory that records every call.
 */
class FakeDocuments implements DocumentResolver {
	private static final int SHARED = DocumentsContract.Document.FLAG_SUPPORTS_RENAME |
			DocumentsContract.Document.FLAG_SUPPORTS_MOVE |
			DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
	static final int FILE_FLAGS = SHARED |
			DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
	static final int FOLDER_FLAGS = SHARED |
			DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;

	static final class Node {
		final String id;
		final boolean folder;
		String name;
		int flags;
		Node parent;
		byte[] bytes = new byte[0];
		long modified = ProjectFiles.UNKNOWN;

		Node(String id, String name, boolean folder, int flags) {
			this.id = id;
			this.name = name;
			this.folder = folder;
			this.flags = flags;
		}
	}

	final Map<String, Node> nodes = new LinkedHashMap<>();
	final List<String> calls = new ArrayList<>();
	final Node root;
	/** Thrown by every call when set. */
	Exception failure;
	/** Whether names that differ only in case are one name. */
	boolean foldsCase;
	int fileFlags = FILE_FLAGS;
	int folderFlags = FOLDER_FLAGS;
	private int nextId;

	FakeDocuments() {
		root = add(null, "root", true);
	}

	Node put(String path, String text) {
		Node node = add(folder(path.substring(0,
				Math.max(0, path.lastIndexOf('/')))),
				path.substring(path.lastIndexOf('/') + 1), false);
		node.bytes = text.getBytes(StandardCharsets.UTF_8);
		return node;
	}

	Node folder(String path) {
		Node folder = root;
		for (String name : path.split("/")) {
			if (name.isEmpty()) {
				continue;
			}
			Node next = null;
			for (Node child : children(folder)) {
				if (child.name.equals(name)) {
					next = child;
				}
			}
			folder = next != null ? next : add(folder, name, true);
		}
		return folder;
	}

	Node add(Node parent, String name, boolean folder) {
		Node node = new Node("doc" + nextId++, name, folder,
				folder ? folderFlags : fileFlags);
		node.parent = parent;
		nodes.put(node.id, node);
		return node;
	}

	String text(Node node) {
		return new String(node.bytes, StandardCharsets.UTF_8);
	}

	List<Node> children(Node folder) {
		List<Node> children = new ArrayList<>();
		for (Node node : nodes.values()) {
			if (node.parent == folder) {
				children.add(node);
			}
		}
		return children;
	}

	@Override
	public Document document(String id) throws IOException {
		return document(node("document", id));
	}

	@Override
	public List<Document> children(String folderId)
			throws IOException {
		Node folder = node("children", folderId);
		if (!folder.folder) {
			throw new IllegalArgumentException("not a folder");
		}
		List<Document> documents = new ArrayList<>();
		for (Node node : children(folder)) {
			documents.add(document(node));
		}
		return documents;
	}

	@Override
	public byte[] read(String id, long limit) throws IOException {
		byte[] bytes = node("read", id).bytes;
		return bytes.length > limit ? null : bytes.clone();
	}

	@Override
	public void write(String id, byte[] bytes) throws IOException {
		node("write", id).bytes = bytes.clone();
	}

	@Override
	public String create(String folderId, String name, boolean folder)
			throws IOException {
		Node parent = node("create", folderId);
		String stored = name;
		for (int i = 1; taken(parent, stored, null); ++i) {
			stored = name + " (" + i + ")";
		}
		return add(parent, stored, folder).id;
	}

	@Override
	public String rename(String id, String name) throws IOException {
		Node node = node("rename", id);
		if (taken(node.parent, name, node)) {
			throw new IllegalStateException("name is taken");
		}
		node.name = name;
		return id;
	}

	@Override
	public String move(String id, String fromFolderId, String toFolderId)
			throws IOException {
		Node node = node("move", id);
		node.parent = nodes.get(toFolderId);
		return id;
	}

	@Override
	public void delete(String id) throws IOException {
		Node node = node("delete", id);
		for (Node child : children(node)) {
			delete(child.id);
		}
		nodes.remove(node.id);
	}

	private static Document document(Node node) {
		return new Document(node.id, node.name, node.folder, node.flags,
				node.folder ? ProjectFiles.UNKNOWN : node.bytes.length,
				node.modified);
	}

	private boolean taken(Node folder, String name, Node except) {
		for (Node child : children(folder)) {
			if (child != except && (foldsCase
					? child.name.equalsIgnoreCase(name)
					: child.name.equals(name))) {
				return true;
			}
		}
		return false;
	}

	private Node node(String call, String id) throws IOException {
		calls.add(call + " " + id);
		if (failure instanceof IOException e) {
			throw e;
		} else if (failure instanceof RuntimeException e) {
			throw e;
		}
		Node node = nodes.get(id);
		if (node == null) {
			throw new FileNotFoundException(id);
		}
		return node;
	}
}
