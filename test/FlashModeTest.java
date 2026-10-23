import com.rizky.mtkflash.FlashMode;

public class FlashModeTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("OK   " + name);
        } else {
            fail++;
            System.out.println("GAGAL " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) {
        FlashMode[] all = FlashMode.values();
        check("jumlah mode = 3", all.length == 3, String.valueOf(all.length));

        check("urutan 0 Download Only", all[0] == FlashMode.DOWNLOAD_ONLY, all[0].label);
        check("urutan 1 Firmware Upgrade", all[1] == FlashMode.FIRMWARE_UPGRADE, all[1].label);
        check("urutan 2 Format All + Download", all[2] == FlashMode.FORMAT_ALL_DOWNLOAD, all[2].label);

        check("Download Only tidak flash semua", !FlashMode.DOWNLOAD_ONLY.flashAll(), "flashAll");
        check("Firmware Upgrade flash semua", FlashMode.FIRMWARE_UPGRADE.flashAll(), "flashAll");
        check("Format All flash semua", FlashMode.FORMAT_ALL_DOWNLOAD.flashAll(), "flashAll");

        check("Download Only tanpa hapus", !FlashMode.DOWNLOAD_ONLY.wipeFirst(), "wipeFirst");
        check("Firmware Upgrade tanpa hapus", !FlashMode.FIRMWARE_UPGRADE.wipeFirst(), "wipeFirst");
        check("Format All hapus dulu", FlashMode.FORMAT_ALL_DOWNLOAD.wipeFirst(), "wipeFirst");

        check("Download Only tetap tulis userdata",
                !FlashMode.DOWNLOAD_ONLY.keepData("userdata"), "keepData");
        check("Firmware Upgrade lewati userdata",
                FlashMode.FIRMWARE_UPGRADE.keepData("userdata"), "keepData");
        check("Firmware Upgrade lewati metadata",
                FlashMode.FIRMWARE_UPGRADE.keepData("metadata"), "keepData");
        check("Firmware Upgrade lewati cache",
                FlashMode.FIRMWARE_UPGRADE.keepData("cache"), "keepData");
        check("Firmware Upgrade tulis boot",
                !FlashMode.FIRMWARE_UPGRADE.keepData("boot"), "keepData");
        check("Firmware Upgrade tulis super",
                !FlashMode.FIRMWARE_UPGRADE.keepData("super"), "keepData");
        check("Firmware Upgrade tulis preloader",
                !FlashMode.FIRMWARE_UPGRADE.keepData("preloader"), "keepData");
        check("Format All tidak lewati userdata",
                !FlashMode.FORMAT_ALL_DOWNLOAD.keepData("userdata"), "keepData");
        check("keepData aman untuk null",
                !FlashMode.FIRMWARE_UPGRADE.keepData(null), "keepData null");
        check("keepData huruf besar",
                FlashMode.FIRMWARE_UPGRADE.keepData("USERDATA"), "keepData upper");

        check("byIndex(-1) jatuh ke Download Only",
                FlashMode.byIndex(-1) == FlashMode.DOWNLOAD_ONLY, "byIndex -1");
        check("byIndex(9) jatuh ke Download Only",
                FlashMode.byIndex(9) == FlashMode.DOWNLOAD_ONLY, "byIndex 9");
        check("byIndex(2) Format All",
                FlashMode.byIndex(2) == FlashMode.FORMAT_ALL_DOWNLOAD, "byIndex 2");

        String[] labels = FlashMode.labels();
        check("label 3 baris", labels.length == 3, String.valueOf(labels.length));
        check("label memuat Download Only", labels[0].contains("Download Only"), labels[0]);
        check("label memuat Firmware Upgrade", labels[1].contains("Firmware Upgrade"), labels[1]);
        check("label memuat Format All + Download",
                labels[2].contains("Format All + Download"), labels[2]);

        System.out.println("hasil: " + pass + " lulus, " + fail + " gagal");
        System.exit(fail == 0 ? 0 : 1);
    }
}
