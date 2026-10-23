import com.rizky.mtkflash.ScatterParser;

import java.io.File;

public class ScatterOnlyTest {

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
        txt(args[0]);
        System.out.println();
        mtkXml(args[1]);
        System.out.println();
        altXml(args[2]);
        System.out.println();
        System.out.println("hasil: " + pass + " lulus, " + fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void txt(String path) throws Exception {
        System.out.println("=== scatter teks: " + path + " ===");
        ScatterParser.Scatter sc = ScatterParser.parse(new File(path));
        check("format terdeteksi teks", !sc.xml, "" + sc.xml);
        check("platform MT6768", "MT6768".equals(sc.platform), sc.platform);
        check("partisi > 0", sc.partitions.size() > 0, "" + sc.partitions.size());
        ScatterParser.Partition boot = sc.find("boot");
        check("ada boot", boot != null, boot == null ? "null" : boot.name);
        if (boot != null) {
            check("boot punya image", boot.hasImage(), boot.fileName);
        }
    }

    static void mtkXml(String path) throws Exception {
        System.out.println("=== scatter xml (partition_index): " + path + " ===");
        ScatterParser.Scatter sc = ScatterParser.parse(new File(path));
        check("format terdeteksi xml", sc.xml, "" + sc.xml);
        check("platform MT6768", "MT6768".equals(sc.platform), sc.platform);
        check("project tssi", "tssi".equals(sc.project), sc.project);
        check("storage EMMC", "HW_STORAGE_EMMC".equals(sc.storage), sc.storage);
        check("config V1.1.2", "V1.1.2".equals(sc.configVersion), sc.configVersion);
        check("block size 0x20000", sc.blockSize == 0x20000, "0x" + Long.toHexString(sc.blockSize));
        check("jumlah partisi = 4", sc.partitions.size() == 4, "" + sc.partitions.size());

        ScatterParser.Partition boot = sc.find("boot");
        check("cari boot", boot != null, boot == null ? "null" : boot.name);
        if (boot != null) {
            check("boot file boot.img", "boot.img".equals(boot.fileName), boot.fileName);
            check("boot alamat 0x8000", boot.physicalAddr == 0x8000,
                    "0x" + Long.toHexString(boot.physicalAddr));
            check("boot ukuran 32 MB", boot.size == 0x2000000, "" + boot.size);
            check("boot region EMMC_USER", "EMMC_USER".equals(boot.region), boot.region);
            check("boot is_download", boot.download, "" + boot.download);
        }

        ScatterParser.Partition pre = sc.find("preloader");
        check("cari preloader", pre != null, pre == null ? "null" : pre.name);
        if (pre != null) {
            check("preloader region EMMC_BOOT_1", "EMMC_BOOT_1".equals(pre.region), pre.region);
        }

        ScatterParser.Partition ud = sc.find("userdata");
        check("cari userdata", ud != null, ud == null ? "null" : ud.name);
        if (ud != null) {
            check("userdata NONE -> tanpa image", !ud.hasImage(), ud.fileName);
            check("userdata is_download=false", !ud.download, "" + ud.download);
        }
    }

    static void altXml(String path) throws Exception {
        System.out.println("=== scatter xml (tag self-closing): " + path + " ===");
        ScatterParser.Scatter sc = ScatterParser.parse(new File(path));
        check("format terdeteksi xml", sc.xml, "" + sc.xml);
        check("jumlah partisi = 4", sc.partitions.size() == 4, "" + sc.partitions.size());
        ScatterParser.Partition dtbo = sc.find("dtbo");
        check("cari dtbo", dtbo != null, dtbo == null ? "null" : dtbo.name);
        if (dtbo != null) {
            check("dtbo file dtbo.img", "dtbo.img".equals(dtbo.fileName), dtbo.fileName);
            check("dtbo ukuran 8 MB", dtbo.size == 0x800000, "" + dtbo.size);
            check("dtbo region UFS_LU2", "UFS_LU2".equals(dtbo.region), dtbo.region);
        }
        ScatterParser.Partition sup = sc.find("super");
        check("super 4 GB", sup != null && sup.size == 0x100000000L,
                sup == null ? "null" : "" + sup.size);
        check("platform dari atribut", "MT6789".equals(sc.platform), sc.platform);
    }
}
