import com.rizky.mtkflash.DaLoader;
import com.rizky.mtkflash.ScatterParser;

import java.io.File;
import java.util.List;

/**
 * Uji logika parser di JVM biasa (tanpa Android).
 *
 * Kelas ScatterParser dan DaLoader hanya memakai java.io/java.util, jadi
 * bisa diuji langsung dengan javac + java. Ini memverifikasi bagian yang
 * tidak butuh perangkat fisik.
 */
public class ParserTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("pakai: ParserTest <scatter> <da-file>");
            System.exit(2);
        }

        System.out.println("=== uji ScatterParser ===");
        ScatterParser.Scatter sc = ScatterParser.parse(new File(args[0]));

        check("platform terbaca", "MT6768".equals(sc.platform), sc.platform);
        check("project terbaca", "tssi".equals(sc.project), sc.project);
        check("config version", "V1.1.2".equals(sc.configVersion), sc.configVersion);
        check("block size", sc.blockSize == 0x20000, "0x" + Long.toHexString(sc.blockSize));
        check("jumlah partisi = 4", sc.partitions.size() == 4, "" + sc.partitions.size());

        ScatterParser.Partition boot = sc.find("boot");
        check("cari partisi boot", boot != null, boot == null ? "null" : boot.name);
        if (boot != null) {
            check("alamat boot 0x8000", boot.physicalAddr == 0x8000,
                    "0x" + Long.toHexString(boot.physicalAddr));
            check("ukuran boot 32 MB", boot.size == 0x2000000, "" + boot.size);
            check("region boot EMMC_USER", "EMMC_USER".equals(boot.region), boot.region);
            check("boot is_download", boot.download, "" + boot.download);
            check("boot punya image", boot.hasImage(), boot.fileName);
        }

        ScatterParser.Partition ud = sc.find("userdata");
        check("cari userdata", ud != null, ud == null ? "null" : ud.name);
        if (ud != null) {
            check("userdata tanpa image (NONE)", !ud.hasImage(), ud.fileName);
            check("userdata is_download=false", !ud.download, "" + ud.download);
        }

        ScatterParser.Partition dlkm = sc.find("vendor_dlkm");
        check("cari vendor_dlkm", dlkm != null, dlkm == null ? "null" : dlkm.name);
        if (dlkm != null) {
            check("alamat vendor_dlkm 0x208000", dlkm.physicalAddr == 0x208000,
                    "0x" + Long.toHexString(dlkm.physicalAddr));
            check("ukuran vendor_dlkm 16 MB", dlkm.size == 0x1000000, "" + dlkm.size);
        }

        check("partisi tidak dikenal -> null", sc.find("tidak_ada") == null, "");

        System.out.println();
        System.out.println("=== uji DaLoader ===");
        File daFile = new File(args[1]);
        List<DaLoader.DaImage> list = DaLoader.load(daFile);
        check("DA terbaca", !list.isEmpty(), list.size() + " image");
        for (int i = 0; i < Math.min(list.size(), 5); i++) {
            DaLoader.DaImage d = list.get(i);
            System.out.println("        " + d);
        }
        if (!list.isEmpty()) {
            DaLoader.DaImage d0 = list.get(0);
            check("image pertama punya data", d0.data != null && d0.data.length == d0.size,
                    d0.size + " byte");
            check("load address wajar", d0.loadAddr != 0,
                    "0x" + Integer.toHexString(d0.loadAddr));
            // penanda MMM harus ada di awal image
            check("magic MMM di awal image",
                    d0.size >= 4 && (d0.data[0] & 0xFF) == 0x4D
                            && (d0.data[1] & 0xFF) == 0x4D
                            && (d0.data[2] & 0xFF) == 0x4D,
                    String.format("%02X %02X %02X", d0.data[0], d0.data[1], d0.data[2]));
        }

        System.out.println();
        System.out.println("hasil: " + pass + " lulus, " + fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }
}
