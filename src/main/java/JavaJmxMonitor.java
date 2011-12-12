import com.sun.management.OperatingSystemMXBean;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.management.ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE;
import static java.lang.management.ManagementFactory.newPlatformMXBeanProxy;

/**
 * This program is designed to show how remote JMX calls can be used to
 * retrieve metrics of remote JVMs. To monitor other JVMs, make sure these are
 * started with:
 *
 * -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false
 *
 * And then simply subsitute the IP address in the JMX url by the IP address
 * of the node.
 *
 * @author Galder Zamarreño
 */
public class JavaJmxMonitor {

   public static void main(String[] args) throws Exception {
//      // If running in same VM...
//      final MBeanServerConnection con = ManagementFactory.getPlatformMBeanServer();

      // If remote access, start program with:
      // -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false
      final MBeanServerConnection con = JMXConnectorFactory.connect(
            new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi"), null)
         .getMBeanServerConnection();

      final ScheduledExecutorService exec = Executors.newScheduledThreadPool(10);

      exec.scheduleAtFixedRate(new PrintCpuUsage(con), 1, 1, TimeUnit.SECONDS);
      exec.scheduleAtFixedRate(new PrintMemoryUsage(con), 1, 1, TimeUnit.SECONDS);
      exec.scheduleAtFixedRate(new PrintGcActivity(con), 1, 1, TimeUnit.SECONDS);
      new CpuIntensiveTask().start();
   }
   
   static class CpuIntensiveTask extends Thread {
      @Override
      public void run() {
         while (true) {
            // If we garbage collect all the time, memory retrieval hangs
            // System.gc();
            byte[] b = new byte[1024];
         }
      }
   }

   static final String PROCESS_CPU_TIME_ATTR = "ProcessCpuTime";
   static final String PROCESSING_CAPACITY_ATTR = "ProcessingCapacity";
   static final String PROCESS_UP_TIME = "Uptime";
   static final ObjectName OS_NAME = getOSName();
   static final ObjectName RUNTIME_NAME = getRuntimeName();
   static final NumberFormat PERCENT_FORMATTER = NumberFormat.getPercentInstance();

   private static class PrintGcActivity implements Runnable {
      final MBeanServerConnection con;
      final int procCount;
      final long cpuTimeMultiplier;
      List<GarbageCollectorMXBean> gcMbeans;
      long gcTime;
      long prevGcTime;
      long upTime;
      long prevUpTime;

      private PrintGcActivity(MBeanServerConnection con) throws Exception {
         this.con = con;
         OperatingSystemMXBean os = ManagementFactory.newPlatformMXBeanProxy(con,
            ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
         procCount = os.getAvailableProcessors();
         cpuTimeMultiplier = getCpuMultiplier(con);
      }

      public void run() {
         try {
            prevUpTime = upTime;
            prevGcTime = gcTime;

            gcMbeans = getGarbageCollectorMXBeans();
            gcTime = -1;
            for (GarbageCollectorMXBean gcBean : gcMbeans)
               gcTime += gcBean.getCollectionTime();

            long processGcTime = gcTime * 1000000 / procCount;
            long prevProcessGcTime = prevGcTime * 1000000 / procCount;
            long processGcTimeDiff = processGcTime - prevProcessGcTime;

            Long jmxUpTime = (Long) con.getAttribute(RUNTIME_NAME, PROCESS_UP_TIME);
            upTime = jmxUpTime;
            long upTimeDiff = (upTime * 1000000) - (prevUpTime * 1000000);

            long gcUsage = upTimeDiff > 0 ? Math.min((long)
                  (1000 * (float)processGcTimeDiff / (float)upTimeDiff), 1000) : 0;

            String gcDetail = formatPercent(gcUsage * 0.1d);

            System.out.println("GC activity: " + gcDetail);
         } catch (Exception e) {
            e.printStackTrace();
         }
      }

      private List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() throws Exception {
         List<GarbageCollectorMXBean> gcMbeans = null;
         // TODO: List changes, so can't really cache apparently, but how performant is this?
         if (con != null) {
            ObjectName gcName = new ObjectName(GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*");
            Set<ObjectName> mbeans = con.queryNames(gcName, null);
            if (mbeans != null) {
               gcMbeans = new ArrayList<GarbageCollectorMXBean>();
               for (ObjectName on : mbeans) {
                  String name = GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",name=" + on.getKeyProperty("name");
                  GarbageCollectorMXBean mbean = newPlatformMXBeanProxy(con, name, GarbageCollectorMXBean.class);
                  gcMbeans.add(mbean);
               }
            }
         }
         return gcMbeans;
      }
   }

   private static class PrintMemoryUsage implements Runnable {
      static final NumberFormat DECIMAL_FORMATTER = NumberFormat.getNumberInstance();
      final MBeanServerConnection con;
      final MemoryMXBean memMbean;
      long genUsed;
      long genCapacity;
      long genMaxCapacity;

      static {
         DECIMAL_FORMATTER.setGroupingUsed(true);
         DECIMAL_FORMATTER.setMaximumFractionDigits(2);
      }

      private PrintMemoryUsage(MBeanServerConnection con) throws IOException {
         this.con = con;
         this.memMbean = ManagementFactory.newPlatformMXBeanProxy(con,
               ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
      }

      public void run() {
         try {
            MemoryUsage mem = memMbean.getHeapMemoryUsage();
            genUsed = mem.getUsed();
            genCapacity = mem.getCommitted();
            genMaxCapacity = mem.getMax();

            System.out.printf("Memory usage: used=%s B, size=%s B, max=%s B \n",
                  formatDecimal(genUsed), formatDecimal(genCapacity),
                  formatDecimal(genMaxCapacity));
         } catch (Exception e) {
            e.printStackTrace();
         }
      }

      private String formatDecimal(long value) {
         return DECIMAL_FORMATTER.format(value);
      }

   }

   private static class PrintCpuUsage implements Runnable {
      final MBeanServerConnection con;
      final long cpuTimeMultiplier;
      final int procCount;
      long cpuTime;
      long prevCpuTime;
      long upTime;
      long prevUpTime;

      static {
         PERCENT_FORMATTER.setMinimumFractionDigits(1);
         PERCENT_FORMATTER.setMaximumIntegerDigits(3);
      }

      private PrintCpuUsage(MBeanServerConnection con) throws Exception {
         this.con = con;
         this.cpuTimeMultiplier = getCpuMultiplier(con);
         OperatingSystemMXBean os = ManagementFactory.newPlatformMXBeanProxy(con,
            ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
         procCount = os.getAvailableProcessors();
      }

      public void run() {
         try {
            prevCpuTime = cpuTime;
            prevUpTime = upTime;

            Long jmxCpuTime = (Long) con.getAttribute(OS_NAME, PROCESS_CPU_TIME_ATTR);
            cpuTime = jmxCpuTime * cpuTimeMultiplier;
            Long jmxUpTime = (Long) con.getAttribute(RUNTIME_NAME, PROCESS_UP_TIME);
            upTime = jmxUpTime;
            long upTimeDiff = (upTime * 1000000) - (prevUpTime * 1000000);

            long procTimeDiff = (cpuTime / procCount) - (prevCpuTime / procCount);

            long cpuUsage = upTimeDiff > 0 ? Math.min((long)
               (1000 * (float) procTimeDiff / (float) upTimeDiff), 1000) : 0;

            String cpuDetail = formatPercent(cpuUsage * 0.1d);
            
            System.out.println("Cpu usage: " + cpuDetail);
         } catch (Exception e) {
            e.printStackTrace();
         }
      }

   }

   private static ObjectName getOSName() {
      try {
         return new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
      } catch (MalformedObjectNameException ex) {
         throw new RuntimeException(ex);
      }
   }

   private static ObjectName getRuntimeName() {
      try {
         return new ObjectName(ManagementFactory.RUNTIME_MXBEAN_NAME);
      } catch (MalformedObjectNameException ex) {
         throw new RuntimeException(ex);
      }
   }

   private static long getCpuMultiplier(MBeanServerConnection con) throws Exception {
      Number num;
      try {
         num = (Number) con.getAttribute(OS_NAME, PROCESSING_CAPACITY_ATTR);
      } catch (AttributeNotFoundException e) {
         num = 1;
      }
      return num.longValue();
   }

   public static String formatPercent(double value) {
      return PERCENT_FORMATTER.format(value / 100);
   }

}
