package com.hivemem.backup.cli;

import com.hivemem.backup.BackupRestoreService;
import com.hivemem.backup.BackupService;
import com.hivemem.backup.Manifest;
import com.hivemem.backup.RestoreMode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

@Component
@Profile("backup")
public class BackupCommand implements ApplicationRunner {

    private final BackupService exportSvc;
    private final BackupRestoreService restoreSvc;
    private final IntConsumer exitFn;

    public BackupCommand(BackupService exportSvc, BackupRestoreService restoreSvc,
                         ConfigurableApplicationContext ctx) {
        this(exportSvc, restoreSvc, code -> {
            SpringApplication.exit(ctx, () -> code);
            System.exit(code);
        });
    }

    BackupCommand(BackupService exportSvc, BackupRestoreService restoreSvc, IntConsumer exitFn) {
        this.exportSvc = exportSvc;
        this.restoreSvc = restoreSvc;
        this.exitFn = exitFn;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.size() < 2 || !"backup".equals(nonOption.get(0))) {
            System.err.println("Usage: backup export --out <path>");
            System.err.println("       backup restore --in <path> [--mode=move|clone] [--force] [--yes]");
            exit(2);
            return;
        }
        try {
            switch (nonOption.get(1)) {
                case "export" -> doExport(args);
                case "restore" -> doRestore(args);
                default -> {
                    System.err.println("Unknown subcommand: " + nonOption.get(1));
                    exit(2);
                    return;
                }
            }
            exit(0);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            e.printStackTrace(System.err);
            exit(1);
        }
    }

    private void doExport(ApplicationArguments args) throws Exception {
        String out = optionValue(args, "out");
        if (out == null) throw new IllegalArgumentException("--out required");
        Path outPath = Path.of(out).toAbsolutePath();
        if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
        try (var os = new BufferedOutputStream(new FileOutputStream(outPath.toFile()))) {
            Manifest m = exportSvc.export(os);
            System.out.println("Exported instance " + m.instanceId() + " to " + outPath);
            System.out.println("Cells:" + m.counts().cells()
                    + " Attachments:" + m.counts().attachments()
                    + " OpsLog:" + m.opsLog().entryCount());
        }
    }

    private void doRestore(ApplicationArguments args) throws Exception {
        String in = optionValue(args, "in");
        if (in == null) throw new IllegalArgumentException("--in required");
        String modeStr = optionValueOr(args, "mode", "move");
        RestoreMode mode = RestoreMode.valueOf(modeStr.toUpperCase());
        boolean force = args.containsOption("force");
        boolean yes = args.containsOption("yes");

        if (mode == RestoreMode.MOVE && !yes) {
            System.out.println("⚠  Restore mode: MOVE — target will adopt the source instance_id.");
            System.out.println("   Make sure the source instance is no longer running.");
            System.out.print("Continue? (yes/no) ");
            String answer = new java.util.Scanner(System.in).nextLine().trim().toLowerCase();
            if (!"yes".equals(answer)) {
                System.out.println("Aborted.");
                return;
            }
        }

        if (force) {
            // --force truncates the target DB and empties its S3 bucket BEFORE the archive
            // import runs. If the import then fails partway through, the target is left
            // EMPTY, not restored to its prior state — there's no safe way to import into a
            // live target first without risking PK conflicts against the very data --force is
            // meant to replace. Make sure the operator knows this before it starts.
            System.out.println("⚠  --force: target will be TRUNCATED/EMPTIED before the import runs.");
            System.out.println("   If the import fails partway through, the target is left EMPTY —");
            System.out.println("   not restored to its prior state. Keep a separate backup of the");
            System.out.println("   target until this restore is confirmed successful.");
        }

        try (var is = new BufferedInputStream(new FileInputStream(in))) {
            restoreSvc.restore(is, mode, force);
        }
        System.out.println("Restore complete (mode=" + mode + ", force=" + force + ").");
    }

    private static String optionValue(ApplicationArguments args, String name) {
        var v = args.getOptionValues(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static String optionValueOr(ApplicationArguments args, String name, String def) {
        String v = optionValue(args, name);
        return v == null ? def : v;
    }

    private void exit(int code) {
        exitFn.accept(code);
    }
}
