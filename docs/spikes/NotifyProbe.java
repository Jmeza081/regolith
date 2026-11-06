import jcifs.*; import jcifs.config.PropertyConfiguration; import jcifs.context.BaseContext;
import jcifs.smb.*; import java.nio.file.*; import java.util.*;
public class NotifyProbe {
  public static void main(String[] a) throws Exception {
    Properties p = new Properties();
    p.setProperty("jcifs.smb.client.minVersion", "SMB202");
    p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
    p.setProperty("jcifs.smb.client.responseTimeout", "600000");
    p.setProperty("jcifs.smb.client.soTimeout", "600000");
    CIFSContext ctx = new BaseContext(new PropertyConfiguration(p))
      .withCredentials(new NtlmPasswordAuthenticator("", "rgtest", Files.readString(Path.of(a[0])).trim()));
    SmbFile dir = new SmbFile("smb://127.0.0.1/RGNOTIFY/" + (a.length > 2 ? a[2] : ""), ctx);
    System.out.println("list: " + Arrays.toString(dir.list()));
    boolean recursive = Boolean.parseBoolean(a[1]);
    try (SmbWatchHandle h = dir.watch(FileNotifyInformation.FILE_NOTIFY_CHANGE_NAME | FileNotifyInformation.FILE_NOTIFY_CHANGE_SIZE | FileNotifyInformation.FILE_NOTIFY_CHANGE_LAST_WRITE, recursive)) {
      System.out.println("watching recursive=" + recursive); System.out.flush();
      while (true) {
        List<FileNotifyInformation> r = h.watch();
        System.out.println(System.currentTimeMillis() + " batch " + (r == null ? "null(overflow?)" : r.size()));
        if (r != null) for (FileNotifyInformation f : r) System.out.println("  action=" + f.getAction() + " " + f.getFileName());
        System.out.flush();
      }
    }
  }
}
