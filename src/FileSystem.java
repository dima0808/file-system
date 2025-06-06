import java.util.*;

public class FileSystem {
  private final FileDescriptor[] descriptors;
  private final FileDescriptor rootDirectory;
  private final Map<Integer, OpenFile> openFiles;
  private int currentDirId = 0;

  private static class OpenFile {
    int descriptorId;
    int position;

    OpenFile(int descriptorId) {
      this.descriptorId = descriptorId;
      this.position = 0;
    }
  }

  private static class PathResult {
    FileDescriptor dir;
    String name;
    int dirId;
  }

  private PathResult resolvePath(String pathname, boolean resolveLastSymlink) {
    String[] parts = pathname.split("/");
    FileDescriptor current;
    int currentId;
    if (pathname.startsWith("/")) {
      current = rootDirectory;
      currentId = 0;
    } else {
      current = descriptors[currentDirId];
      currentId = currentDirId;
    }
    for (int i = 0; i < parts.length - 1; i++) {
      if (parts[i].isEmpty()) continue;
      if (!current.entries.containsKey(parts[i]))
        throw new IllegalArgumentException("Шлях не знайдено: " + parts[i]);
      int id = current.entries.get(parts[i]);
      FileDescriptor next = descriptors[id];
      if (next.type == FileType.SYMLINK) {
        if (!resolveLastSymlink || i < parts.length - 2) {
          String target = next.symlinkTarget;
          return resolvePath(target + "/" + String.join("/", Arrays.copyOfRange(parts, i + 1, parts.length)), resolveLastSymlink);
        }
      }
      if (next.type != FileType.DIRECTORY)
        throw new IllegalArgumentException("Не є директорією: " + parts[i]);
      current = next;
      currentId = id;
    }
    PathResult result = new PathResult();
    result.dir = current;
    result.dirId = currentId;
    result.name = parts[parts.length - 1];
    return result;
  }

  public FileSystem(int maxDescriptors) {
    descriptors = new FileDescriptor[maxDescriptors];
    rootDirectory = new FileDescriptor(FileType.DIRECTORY);
    descriptors[0] = rootDirectory;
    openFiles = new HashMap<>();
  }

  public void create(String pathname) {
    PathResult pr = resolvePath(pathname, false);
    if (pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Файл уже існує");

    int id = getFreeDescriptor();
    if (id == -1)
      throw new IllegalStateException("Немає вільних дескрипторів");

    descriptors[id] = new FileDescriptor(FileType.REGULAR);
    pr.dir.entries.put(pr.name, id);
    System.out.println("Файл '" + pathname + "' створено з дескриптором ID " + id);
  }

  public int open(String pathname) {
    PathResult pr = resolvePath(pathname, true);
    if (!pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Файл не знайдено");
    int id = pr.dir.entries.get(pr.name);
    int fd = getFreeFD();
    openFiles.put(fd, new OpenFile(id));
    System.out.println("Файл '" + pathname + "' відкрито з дескриптором ID " + id + " та файловим дескриптором " + fd);
    return fd;
  }

  public void close(int fd) {
    if (!openFiles.containsKey(fd))
      throw new IllegalArgumentException("Файл не відкрито або вже закрито");

    int descriptorId = openFiles.get(fd).descriptorId;
    openFiles.remove(fd);

    if (descriptors[descriptorId].linkCount == 0 && isDescriptorClosed(descriptorId)) {
      descriptors[descriptorId] = null;
    }

    System.out.println("Файловий дескриптор " + fd + " закрито");
  }

  public void write(int fd, byte[] content) {
    if (!openFiles.containsKey(fd))
      throw new IllegalArgumentException("Невірний файловий дескриптор");

    OpenFile file = openFiles.get(fd);
    FileDescriptor desc = descriptors[file.descriptorId];

    int position = file.position;
    int newSize = position + content.length;

    allocateBlocks(desc, newSize);

    for (int i = 0; i < content.length; i++) {
      int blockIndex = (position + i) / FileSystemState.BLOCK_SIZE;
      int offset = (position + i) % FileSystemState.BLOCK_SIZE;
      desc.blocks.get(blockIndex)[offset] = content[i];
    }

    desc.size = Math.max(desc.size, newSize);
    file.position += content.length;
    System.out.println("Записано " + content.length + " байтів у файловий дескриптор " + fd);
  }

  public byte[] read(int fd, int size) {
    if (!openFiles.containsKey(fd))
      throw new IllegalArgumentException("Невірний файловий дескриптор");

    OpenFile file = openFiles.get(fd);
    FileDescriptor desc = descriptors[file.descriptorId];
    int position = file.position;

    if (position + size > desc.size)
      size = desc.size - position;

    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) {
      int blockIndex = (position + i) / FileSystemState.BLOCK_SIZE;
      int offset = (position + i) % FileSystemState.BLOCK_SIZE;
      result[i] = desc.blocks.get(blockIndex)[offset];
    }

    file.position += size;
    return result;
  }

  public void seek(int fd, int offset) {
    if (!openFiles.containsKey(fd))
      throw new IllegalArgumentException("Невірний файловий дескриптор");

    FileDescriptor desc = descriptors[openFiles.get(fd).descriptorId];

    if (offset < 0 || offset > desc.size)
      throw new IllegalArgumentException("Зміщення поза межами файлу");

    openFiles.get(fd).position = offset;
  }

  public void truncate(String pathname, int size) {
    PathResult pr = resolvePath(pathname, true);
    if (!pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Файл '" + pathname + "' не знайдено");
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor desc = descriptors[id];

    if (size < desc.size) {
      freeBlocks(desc, size);
    } else {
      allocateBlocks(desc, size);
    }

    desc.size = size;
    System.out.println("Файл '" + pathname + "' скорочено до " + size + " байтів");
  }

  public void link(String existingPath, String newPath) {
    PathResult existingPr = resolvePath(existingPath, true);
    FileDescriptor existingDesc = descriptors[existingPr.dir.entries.get(existingPr.name)];

    if (existingDesc.type == FileType.DIRECTORY) {
      throw new IllegalArgumentException("Неможливо створити хард-посилання на директорію");
    }

    PathResult newPr = resolvePath(newPath, false);
    if (newPr.dir.entries.containsKey(newPr.name)) {
      throw new IllegalArgumentException("Файл вже існує на новому шляху");
    }

    int id = existingPr.dir.entries.get(existingPr.name);
    newPr.dir.entries.put(newPr.name, id);
    existingDesc.linkCount++;

    System.out.println("Хард-посилання створено: '" + newPath + "' -> '" + existingPath + "'");
  }

  public void unlink(String path) {
    PathResult pr = resolvePath(path, false);
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor desc = descriptors[id];

    if (desc.type == FileType.DIRECTORY) {
      throw new IllegalArgumentException("Неможливо видалити хард-посилання на директорію");
    }

    pr.dir.entries.remove(pr.name);
    desc.linkCount--;

    if (desc.linkCount == 0 && isDescriptorClosed(id)) {
      descriptors[id] = null;
      System.out.println("Файл '" + path + "' повністю видалено (всі посилання знищено)");
    } else {
      System.out.println("Посилання на файл '" + path + "' видалено");
    }
  }

  public void stat(String pathname) {
    PathResult pr = resolvePath(pathname, true);
    if (!pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Файл '" + pathname + "' не знайдено");
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor desc = descriptors[id];
    System.out.println("Info for '" + pathname + "':");
    System.out.println("  Descriptor ID: " + id);
    System.out.println("  Type: " + desc.type);
    System.out.println("  Size: " + desc.size + " bytes");
    System.out.println("  Link count: " + desc.linkCount);
    System.out.println("  Block count: " + desc.blocks.size());
  }

  public void ls(String pathname) {
    PathResult pr = resolvePath(pathname, true);
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor dir = descriptors[id];
    if (dir.type != FileType.DIRECTORY)
      throw new IllegalArgumentException("Не є директорією");
    System.out.println("Файли в '" + pathname + "':");
    for (Map.Entry<String, Integer> entry : dir.entries.entrySet()) {
      String name = entry.getKey();
      int eid = entry.getValue();
      FileDescriptor desc = descriptors[eid];
      System.out.printf("  %-15s | ID: %-3d | Size: %-4d | Links: %d | Type: %s%n", name, eid, desc.size, desc.linkCount, desc.type);
    }
  }

  public void mkdir(String pathname) {
    PathResult pr = resolvePath(pathname, false);
    if (pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Директорія вже існує");
    int id = getFreeDescriptor();
    if (id == -1)
      throw new IllegalStateException("Немає вільних дескрипторів");
    FileDescriptor dir = new FileDescriptor(FileType.DIRECTORY);
    dir.entries.put(".", id);
    dir.entries.put("..", pr.dirId);
    descriptors[id] = dir;
    pr.dir.entries.put(pr.name, id);
    System.out.println("Директорія '" + pathname + "' створена");
  }

  public void rmdir(String pathname) {
    PathResult pr = resolvePath(pathname, false);
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor dir = descriptors[id];
    if (dir.type != FileType.DIRECTORY)
      throw new IllegalArgumentException("Не є директорією");
    if (dir.entries.size() > 2)
      throw new IllegalArgumentException("Директорія не порожня");
    pr.dir.entries.remove(pr.name);
    descriptors[id] = null;
    System.out.println("Директорія '" + pathname + "' видалена");
  }

  public void cd(String pathname) {
    PathResult pr = resolvePath(pathname, true);
    int id = pr.dir.entries.get(pr.name);
    FileDescriptor dir = descriptors[id];
    if (dir.type != FileType.DIRECTORY)
      throw new IllegalArgumentException("Не є директорією");
    currentDirId = id;
    System.out.println("Змінено поточну директорію на '" + pathname + "'");
  }

  public void symlink(String target, String pathname) {
    if (target.length() > FileSystemState.BLOCK_SIZE)
      throw new IllegalArgumentException("Символьне посилання занадто довге");
    PathResult pr = resolvePath(pathname, false);
    if (pr.dir.entries.containsKey(pr.name))
      throw new IllegalArgumentException("Файл вже існує");
    int id = getFreeDescriptor();
    if (id == -1)
      throw new IllegalStateException("Немає вільних дескрипторів");
    FileDescriptor link = new FileDescriptor(FileType.SYMLINK);
    link.symlinkTarget = target;
    link.size = target.length();
    descriptors[id] = link;
    pr.dir.entries.put(pr.name, id);
    System.out.println("Символьне посилання '" + pathname + "' -> '" + target + "' створено");
  }

  private int getFreeDescriptor() {
    for (int i = 0; i < descriptors.length; i++) {
      if (descriptors[i] == null)
        return i;
    }
    return -1;
  }

  private int getFreeFD() {
    int fd = 0;
    while (openFiles.containsKey(fd)) {
      fd++;
    }
    return fd;
  }

  private boolean isDescriptorClosed(int id) {
    return openFiles.values().stream().noneMatch(f -> f.descriptorId == id);
  }

  private void allocateBlocks(FileDescriptor desc, int size) {
    int required = (size + FileSystemState.BLOCK_SIZE - 1) / FileSystemState.BLOCK_SIZE;
    while (desc.blocks.size() < required) {
      desc.blocks.add(new byte[FileSystemState.BLOCK_SIZE]);
    }
  }

  private void freeBlocks(FileDescriptor desc, int size) {
    int required = (size + FileSystemState.BLOCK_SIZE - 1) / FileSystemState.BLOCK_SIZE;
    while (desc.blocks.size() > required) {
      desc.blocks.remove(desc.blocks.size() - 1);
    }
  }
}
