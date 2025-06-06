public class Main {
  public static void main(String[] args) {
    FileSystem fs = new FileSystem(FileSystemState.MAX_DESCRIPTORS);

    System.out.println("\n--- Сценарій: create, link, open, write, stat, ls ---");

    fs.create("test.txt");
    fs.link("test.txt", "copy.txt");
    fs.ls();

    int fd = fs.open("test.txt");
    fs.write(fd, "Hello, World!".getBytes());

    fs.stat("test.txt");
    fs.stat("copy.txt");

    fs.seek(fd, 0);
    byte[] data = fs.read(fd, 5);
    System.out.println("Прочитано з test.txt: " + new String(data));

    fs.truncate("test.txt", 5);
    fs.stat("test.txt");

    fs.seek(fd, 0);
    byte[] data2 = fs.read(fd, 20);
    System.out.println("Після truncate: " + new String(data2));

    fs.close(fd);
    fs.unlink("test.txt");
    fs.ls();

    try {
      fs.stat("test.txt");
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }

    fs.unlink("copy.txt");
    fs.ls();
  }
}
