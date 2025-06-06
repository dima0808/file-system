import java.util.*;

public class FileSystem {
  private final FileDescriptor[] descriptors;
  private final FileDescriptor rootDirectory;
  private final Map<Integer, OpenFile> openFiles;

  private static class OpenFile {
    int descriptorId;
    int position;

    OpenFile(int descriptorId) {
      this.descriptorId = descriptorId;
      this.position = 0;
    }
  }

  public FileSystem(int maxDescriptors) {
    descriptors = new FileDescriptor[maxDescriptors];
    rootDirectory = new FileDescriptor(FileType.DIRECTORY);
    openFiles = new HashMap<>();
  }

  public void create(String name) {
    if (name.length() > FileSystemState.MAX_FILENAME_LENGTH)
      throw new IllegalArgumentException("Назва файлу задовга");

    if (rootDirectory.entries.containsKey(name))
      throw new IllegalArgumentException("Файл уже існує");

    int id = getFreeDescriptor();
    if (id == -1)
      throw new IllegalStateException("Немає вільних дескрипторів");

    descriptors[id] = new FileDescriptor(FileType.REGULAR);
    rootDirectory.entries.put(name, id);
    System.out.println("Файл '" + name + "' створено з дескриптором ID " + id);
  }

  public int open(String name) {
    if (!rootDirectory.entries.containsKey(name))
      throw new IllegalArgumentException("Файл не знайдено");

    int id = rootDirectory.entries.get(name);
    int fd = getFreeFD();
    openFiles.put(fd, new OpenFile(id));
    System.out.println("Файл '" + name + "' відкрито з дескриптором ID " + id + " та файловим дескриптором " + fd);
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

  public void truncate(String name, int size) {
    if (!rootDirectory.entries.containsKey(name))
      throw new IllegalArgumentException("Файл не знайдено");

    int id = rootDirectory.entries.get(name);
    FileDescriptor desc = descriptors[id];

    if (size < desc.size) {
      freeBlocks(desc, size);
    } else {
      allocateBlocks(desc, size);
    }

    desc.size = size;
    System.out.println("Файл '" + name + "' скорочено до " + size + " байтів");
  }

  public void link(String existingName, String newName) {
    if (!rootDirectory.entries.containsKey(existingName))
      throw new IllegalArgumentException("Файл '" + existingName + "' не знайдено");

    if (rootDirectory.entries.containsKey(newName))
      throw new IllegalArgumentException("Файл '" + newName + "' вже існує");

    int id = rootDirectory.entries.get(existingName);
    rootDirectory.entries.put(newName, id);
    descriptors[id].linkCount++;

    System.out.println("Жорстке посилання '" + newName + "' створено на '" + existingName + "'");
  }

  public void unlink(String name) {
    if (!rootDirectory.entries.containsKey(name))
      throw new IllegalArgumentException("Файл '" + name + "' не знайдено");

    int id = rootDirectory.entries.get(name);
    rootDirectory.entries.remove(name);
    descriptors[id].linkCount--;

    if (descriptors[id].linkCount == 0 && isDescriptorClosed(id)) {
      descriptors[id] = null;
      System.out.println("Файл '" + name + "' повністю видалено (останнє посилання)");
    } else {
      System.out.println("Посилання на файл '" + name + "' видалено");
    }
  }

  public void stat(String name) {
    if (!rootDirectory.entries.containsKey(name))
      throw new IllegalArgumentException("Файл '" + name + "' не знайдено");

    int id = rootDirectory.entries.get(name);
    FileDescriptor desc = descriptors[id];

    System.out.println("Інформація про файл '" + name + "':");
    System.out.println("  ID дескриптора: " + id);
    System.out.println("  Тип: " + (desc.type == FileType.REGULAR ? "REGULAR" : "DIRECTORY"));
    System.out.println("  Розмір: " + desc.size + " байтів");
    System.out.println("  Кількість посилань: " + desc.linkCount);
    System.out.println("  Кількість блоків: " + desc.blocks.size());
  }

  public void ls() {
    System.out.println("Файли у кореневому каталозі:");
    for (Map.Entry<String, Integer> entry : rootDirectory.entries.entrySet()) {
      String name = entry.getKey();
      int id = entry.getValue();
      FileDescriptor desc = descriptors[id];
      System.out.printf("  %-15s | ID: %-3d | Size: %-4d | Links: %d%n", name, id, desc.size, desc.linkCount);
    }
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
