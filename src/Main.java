public class Main {
  public static void main(String[] args) {
    FileSystem fs = new FileSystem(FileSystemState.MAX_DESCRIPTORS);

    System.out.println("\n--- Операції з файлами та директоріями ---");
    fs.mkdir("dirA");
    fs.mkdir("dirA/subdirB");
    fs.create("dirA/subdirB/file1.txt");
    fs.stat("dirA/subdirB/file1.txt");
    fs.ls("/dirA/subdirB");

    System.out.println("\n--- Операції з символьними посиланнями ---");
    fs.symlink("dirA/subdirB/file1.txt", "dirA/subdirB/link1");
    fs.stat("dirA/subdirB/link1");

    System.out.println("\n--- Операції з жорсткими посиланнями ---");
    fs.link("dirA/subdirB/file1.txt", "dirA/subdirB/file1_hardlink.txt");
    fs.stat("dirA/subdirB/file1.txt");
    fs.stat("dirA/subdirB/file1_hardlink.txt");

    System.out.println("\n--- Операції видалення посилань ---");
    fs.unlink("dirA/subdirB/file1_hardlink.txt");
    fs.stat("dirA/subdirB/file1.txt");

    System.out.println("\n--- Перегляд директорії ---");
    fs.ls("/dirA/subdirB");

    System.out.println("\n--- Зміна директорії та створення файлу ---");
    fs.cd("dirA/subdirB");
    fs.create("file2.txt");
    fs.ls(".");

    System.out.println("\n--- Видалення файлів та директорій ---");
    fs.unlink("file2.txt");
    try {
      fs.rmdir("."); // Має завершитись помилкою, якщо не порожня
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }
    fs.unlink("file1.txt");
    fs.unlink("link1");
    fs.ls("..");
    fs.rmdir(".");

    System.out.println("\n--- Обробка помилок ---");
    try {
      fs.ls("..");
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }
    try {
      fs.link("/dirA", "/dirA/linkToDir"); // Має завершитись помилкою (не можна створити жорстке посилання на директорію)
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }
    try {
      fs.unlink("/dirA"); // Має завершитись помилкою (не можна видалити директорію як файл)
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }
    try {
      fs.stat("/dirA/subdirB/file1.txt"); // Має завершитись помилкою (вже видалено)
    } catch (Exception e) {
      System.out.println("Очікувана помилка: " + e.getMessage());
    }
  }
}
