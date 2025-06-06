import java.util.*;

public class FileDescriptor {
  public FileType type;
  public int size;
  public int linkCount;
  public List<byte[]> blocks;
  public Map<String, Integer> entries; // For directories only
  public String symlinkTarget; // For symlinks only

  public FileDescriptor(FileType type) {
    this.type = type;
    this.size = 0;
    this.linkCount = 1;
    this.blocks = new ArrayList<>();

    if (type == FileType.DIRECTORY) {
      this.entries = new HashMap<>();
    }
    if (type == FileType.SYMLINK) {
      this.symlinkTarget = null;
    }
  }
}
