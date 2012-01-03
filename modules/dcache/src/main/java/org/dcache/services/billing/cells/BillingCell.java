package org.dcache.services.billing.cells;

import diskCacheV111.cells.DateRenderer;
import diskCacheV111.vehicles.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.dcache.cells.CellMessageReceiver;
import org.dcache.cells.CellCommandListener;
import org.dcache.cells.CellInfoProvider;
import org.dcache.cells.CellStub;
import org.dcache.services.billing.db.IBillingInfoAccess;
import org.dcache.services.billing.db.data.DoorRequestData;
import org.dcache.services.billing.db.data.MoverData;
import org.dcache.services.billing.db.data.PnfsBaseInfo;
import org.dcache.services.billing.db.data.PoolCostData;
import org.dcache.services.billing.db.data.PoolHitData;
import org.dcache.services.billing.db.data.StorageData;
import org.dcache.services.billing.db.exceptions.BillingStorageException;
import org.dcache.services.billing.db.exceptions.BillingInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dmg.cells.nucleus.CellInfo;
import dmg.cells.nucleus.EnvironmentAware;
import dmg.util.Args;
import dmg.util.Formats;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.stringtemplate.StringTemplate;

/**
 * <br>
 * This class is responsible for the processing of messages from other domains
 * regarding transfers and pool usage. It calls out to a IBillingInfoAccess
 * implementation to handle persistence of the data.
 *
 *
 * @see IBillingInfoAccess
 * @author arossi
 *
 */
public final class BillingCell
    implements CellMessageReceiver,
               CellCommandListener,
               CellInfoProvider,
               EnvironmentAware
{    /*
     * FIXME: this class has been adapted simply to use the new DAO
     * (IBillingInfoAccess) abstractions, and really needs fuller refactoring to
     * bring it in line with more current cell design. -ALR
     */

    private static final Logger logger =
        LoggerFactory .getLogger(BillingCell.class);
    private final SimpleDateFormat formatter =
        new SimpleDateFormat ("MM.dd HH:mm:ss");
    private final SimpleDateFormat fileNameFormat =
        new SimpleDateFormat("yyyy.MM.dd");
    private final SimpleDateFormat directoryNameFormat =
        new SimpleDateFormat("yyyy" + File.separator + "MM");

    private final Map<String, int[]> map;
    private final Map<String, long[]> poolStatistics;
    private final Map<String, Map<String, long[]>> poolStorageMap;

    /*
     * log file formats per message type
     */
    private Map<String,Object> _environment;

    /*
     * Injected
     */
    private IBillingInfoAccess access;
    private CellStub poolStub;
    private File logsDir;
    private int printMode;
    private boolean disableTxt;

    private int requests;
    private int failed;
    private File currentDbFile;

    public BillingCell() {
        map = new HashMap<String, int[]>();
        poolStatistics = new HashMap<String, long[]>();
        poolStorageMap = new HashMap<String, Map<String, long[]>>();
        disableTxt = true;
        printMode = 0;
        requests = 0;
        failed = 0;
    }

    @Override
    public void setEnvironment(Map<String,Object> environment) {
        _environment = environment;
    }

    /**
     * Initializes values which depend on arguments or properties injected using
     * Spring.
     */
    public void initialize() {
        logger.info("billing logs location {}", logsDir);
        logger.info("disabled {}", disableTxt);
        logger.info("print mode {}", printMode);

        if ((!logsDir.isDirectory()) || (!logsDir.canWrite()))
            throw new IllegalArgumentException("<" + logsDir
                            + "> doesn't exist or is not writeable");

        try {
            if (access != null) {
                access.initialize();
                logger.info("initialized {}", access);
            }
        } catch (Throwable t) {
            logger.warn("Could not start BillingInfoAccess: {}", t.getMessage());
        }
    }

    public String toString() {
        return "Req=" + requests + ";Err=" + failed + ";";
    }

    @Override
    public CellInfo getCellInfo(CellInfo info) {
        return info;
    }

    @Override
    public void getInfo(PrintWriter pw) {
        pw.print(Formats.field("Requests", 20, Formats.RIGHT));
        pw.print(" : ");
        pw.print(Formats.field("" + requests, 6, Formats.RIGHT));
        pw.print(" / ");
        pw.println(Formats.field("" + failed, 6, Formats.LEFT));
        synchronized (map) {
            for (Map.Entry<String, int[]> entry : map.entrySet()) {
                int[] values = entry.getValue();
                pw.print(Formats.field(entry.getKey(), 20, Formats.RIGHT));
                pw.print(" : ");
                pw.print(Formats.field("" + values[0], 6, Formats.RIGHT));
                pw.print(" / ");
                pw.println(Formats.field("" + values[1], 6, Formats.LEFT));
            }
        }
    }

    /**
     * The main cell routine. Depending on the type of cell message and the
     * option sets, it either processes the message for persistent storage or
     * logs the message to a text file (or both).
     *
     * @param msg
     */
    public void messageArrived(InfoMessage info) {
        /*
         * currently we have to ignore 'check'
         */
        if (info.getMessageType().equals("check"))
            return;
        updateMap(info);
        Date thisDate = new Date(info.getTimestamp());
        String output = getFormattedMessage(info);

        logger.info(output);

        maybePersistInfo(info);

        if(!disableTxt) {
            String ext = logInfo(thisDate, output);

            if(info.getResultCode() != 0) {
                logError(output, ext);
            }
        }
    }

    public void messageArrived(Object msg) {
        Date now = new Date();
        String output = formatter.format(now) + " " + msg.toString();

        logger.info(output);

        if (!disableTxt) {
            logInfo(now, output);
        }
    }

    private String getFormattedMessage(InfoMessage msg) {
        String property = "billing.format." + msg.getClass().getSimpleName();
        Object format = _environment.get(property);
        if (format == null) {
            return msg.toString();
        } else {
            StringTemplate template = new StringTemplate(format.toString());
            template.registerRenderer(Date.class, new DateRenderer());
            msg.fillTemplate(template);
            return template.toString();
        }
    }

    /*
     * //////////////////////////////////////////////////////////////////////////
     * Admin command-line methods.
     */

    public Object ac_get_billing_info(Args args) {
        synchronized (map) {
            Object[][] result = new Object[map.size()][];
            Iterator it = map.entrySet().iterator();
            for (int i = 0; it.hasNext() && (i < result.length); i++) {
                Map.Entry entry = (Map.Entry) it.next();
                int[] values = (int[]) entry.getValue();
                result[i] = new Object[2];
                result[i][0] = entry.getKey();
                result[i][1] = new int[2];
                ((int[]) (result[i][1]))[0] = values[0];
                ((int[]) (result[i][1]))[1] = values[1];
            }
            return result;
        }
    }

    public static final String hh_get_pool_statistics = "[<poolName>]";
    public Object ac_get_pool_statistics_$_0_1(Args args) {
        synchronized (poolStatistics) {
            if (args.argc() == 0)
                return poolStatistics;
            HashMap map = (HashMap) poolStorageMap.get(args.argv(0));
            return map == null ? new HashMap() : map;
        }
    }

    public static final String hh_clear_pool_statistics = "";
    public Object ac_clear_pool_statistics(Args args) {
        poolStatistics.clear();
        poolStorageMap.clear();
        return "";
    }

    public static final String hh_dump_pool_statistics = "";
    public String ac_dump_pool_statistics_$_0_1(Args args)
        throws IOException
    {
        dumpPoolStatistics(args.argc() == 0 ? null : args.argv(0));
        return "";
    }

    public static final String hh_get_poolstatus = "[<fileName>]";
    public String ac_get_poolstatus_$_0_1(Args args) {
        PoolStatusCollector collector = new PoolStatusCollector(
                        (args.argc() > 0 ? args.argv(0) : null), this);
        collector.start();
        return collector.getReportFile().toString();
    }

    /**
     * @param name
     * @throws Exception
     */
    private void dumpPoolStatistics(String name)
        throws IOException
    {
        name = name == null ? ("poolFlow-" + fileNameFormat.format(new Date()))
                        : name;
        File report = new File(logsDir, name);
        PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(
                        report)));
        try {
            Set<Map.Entry<String, Map<String, long[]>>> pools = poolStorageMap
                            .entrySet();

            for (Map.Entry<String, Map<String, long[]>> poolEntry : pools) {

                String poolName = poolEntry.getKey();
                Map<String, long[]> map = poolEntry.getValue();

                for (Map.Entry<String, long[]> statiEntry : map.entrySet()) {
                    String className = statiEntry.getKey();
                    long[] counter = statiEntry.getValue();
                    pw.print(poolName);
                    pw.print("  ");
                    pw.print(className);
                    for (int i = 0; i < counter.length; i++) {
                        pw.print("  " + counter[i]);
                    }
                    pw.println("");
                }
            }
        } catch (RuntimeException ee) {
            logger.warn("Exception in dumpPoolStatistics : {}", ee);
            report.delete();
            throw ee;
        } finally {
            pw.close();
        }
        return;
    }

    private void updateMap(InfoMessage info) {
        String key = info.getMessageType() + ":" + info.getCellType();
        synchronized (map) {
            int[] values = map.get(key);

            if (values == null)
                map.put(key, values = new int[2]);

            values[0]++;
            requests++;

            if (info.getResultCode() != 0) {
                failed++;
                values[1]++;
            }
        }
        if (info.getCellType().equals("pool"))
            doStatistics(info);
    }

    /**
     * Calls out to the underlying persistence implementation.
     *
     * @param info
     */
    private void maybePersistInfo(InfoMessage info) {
        if (info == null || access == null)
            return;
        try {
            access.put(convert(info));
        } catch (BillingStorageException e) {
            logger.warn("Can't log billing via BillingInfoAccess: " +
                        e.getMessage(), e);
            logger.info("Trying to reconnect");

            try {
                if (access != null) {
                    access.close();
                    access.initialize();
                }
            } catch (BillingInitializationException ex) {
                logger.warn("Could not restart BillingInfoAccess: {}",
                            ex.getMessage());
            }
        } catch (RuntimeException e) {
            logger.warn("Billing via BillingInfoAccess failed: " +
                        e.getMessage(), e);
        }
    }

    /**
     * If text logging is turned on, writes out the message to a log file.
     *
     * @param info
     * @param thisDate
     * @param output
     * @return
     */
    private String logInfo(Date thisDate, String output) {
        String fileNameExtension = null;
        if (printMode == 0) {
            currentDbFile = logsDir;
            fileNameExtension = fileNameFormat.format(thisDate);
        } else {
            Date date = new Date();
            currentDbFile = new File(logsDir, directoryNameFormat.format(date));
            if (!currentDbFile.exists())
                currentDbFile.mkdirs();
            fileNameExtension = fileNameFormat.format(date);
        }

        File outputFile = new File(currentDbFile, "billing-"
                        + fileNameExtension);
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(outputFile, true));
            pw.println(output);
        } catch (IOException ee) {
            logger.warn("Can't write billing [{}] : {}", outputFile,
                            ee.toString());
        } finally {
            if (pw != null)
                pw.close();
        }
        return fileNameExtension;
    }

    /**
     * @param info
     * @param output
     * @param ext
     */
    private void logError(String output, String ext) {
        File errorFile = new File(currentDbFile, "billing-error-" + ext);
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(errorFile, true));
            pw.println(output);
        } catch (IOException ee) {
            logger.warn("Can't write billing-error : {}", ee.toString());
        } finally {
            if (pw != null)
                pw.close();
        }
    }

    /**
     * @param info
     */
    private void doStatistics(InfoMessage info) {
        if (info instanceof WarningPnfsFileInfoMessage)
            return;
        String cellName = info.getCellName();
        int pos = 0;
        cellName = ((pos = cellName.indexOf("@")) < 1) ? cellName : cellName
                        .substring(0, pos);
        String transactionType = info.getMessageType();
        synchronized (poolStatistics) {
            long[] counters = poolStatistics.get(cellName);
            if (counters == null)
                poolStatistics.put(cellName, counters = new long[4]);

            if (info.getResultCode() != 0) {
                counters[3]++;
            } else if (transactionType.equals("transfer")) {
                counters[0]++;
            } else if (transactionType.equals("restore")) {
                counters[1]++;
            } else if (transactionType.equals("store")) {
                counters[2]++;
            }
            if ((info instanceof PnfsFileInfoMessage)) {
                PnfsFileInfoMessage pnfsInfo = (PnfsFileInfoMessage) info;
                StorageInfo sinfo = (pnfsInfo).getStorageInfo();
                if (sinfo != null) {
                    Map<String, long[]> map = poolStorageMap.get(cellName);
                    if (map == null)
                        poolStorageMap.put(cellName,
                                        map = new HashMap<String, long[]>());

                    String key = sinfo.getStorageClass() + "@" + sinfo.getHsm();

                    counters = map.get(key);

                    if (counters == null)
                        map.put(key, counters = new long[8]);

                    if (info.getResultCode() != 0) {
                        counters[3]++;
                    } else if (transactionType.equals("transfer")) {
                        counters[0]++;
                        MoverInfoMessage mim = (MoverInfoMessage) info;
                        counters[mim.isFileCreated() ? 4 : 5] += mim
                                        .getDataTransferred();
                    } else if (transactionType.equals("restore")) {
                        counters[1]++;
                        counters[6] += pnfsInfo.getFileSize();
                    } else if (transactionType.equals("store")) {
                        counters[2]++;
                        counters[7] += pnfsInfo.getFileSize();
                    }

                }
            }
        }
    }

    /**
     * Converts from the InfoMessage type to the storage type.
     *
     * @param info
     * @return storage object
     */
    private PnfsBaseInfo convert(InfoMessage info) {
        if (info instanceof MoverInfoMessage)
            return new MoverData((MoverInfoMessage) info);
        if (info instanceof DoorRequestInfoMessage)
            return new DoorRequestData((DoorRequestInfoMessage) info);
        if (info instanceof StorageInfoMessage)
            return new StorageData((StorageInfoMessage) info);
        if (info instanceof PoolCostInfoMessage)
            return new PoolCostData((PoolCostInfoMessage) info);
        if (info instanceof PoolHitInfoMessage)
            return new PoolHitData((PoolHitInfoMessage) info);
        return null;
    }

    /**
     * @return the filenameformat
     */
    public SimpleDateFormat getFilenameformat() {
        return fileNameFormat;
    }

    /**
     * @param poolStub
     *            the poolStub to set
     */
    public void setPoolStub(CellStub poolStub) {
        this.poolStub = poolStub;
    }

    /**
     * @return the poolStub
     */
    public CellStub getPoolStub() {
        return poolStub;
    }

    /**
     * @param access
     *            the access to set
     */
    public void setAccess(IBillingInfoAccess access) {
        this.access = access;
    }

    /**
     * @param billingDb
     *            the billingDb to set
     */
    public void setLogsDir(File logsDir) {
        this.logsDir = logsDir;
    }

    /**
     * @param printMode
     *            the printMode to set
     */
    public void setPrintMode(int printMode) {
        this.printMode = printMode;
    }

    /**
     * @param billingDisableTxt
     *            the billingDisableTxt to set
     */
    public void setDisableTxt(boolean disableTxt) {
        this.disableTxt = disableTxt;
    }

    /**
     * @return the logsDir
     */
    public File getLogsDir() {
        return logsDir;
    }
}
