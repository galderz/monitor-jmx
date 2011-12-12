import org.hyperic.sigar.CpuPerc;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;

import javax.management.MBeanServerConnection;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * To run this program, download Sigar distro and point to it to
 * add the native libraries, i.e.
 *
 * -Djava.library.path=/opt/sigar/sigar-bin/lib
 *
 * @author Galder Zamarreño
 */
public class SigarMonitor {

   public static void main(String[] args) throws Exception {
      final MBeanServerConnection con = ManagementFactory.getPlatformMBeanServer();
      final ScheduledExecutorService exec = Executors.newScheduledThreadPool(10);

      exec.scheduleAtFixedRate(new PrintCpuUsage(), 1, 1, TimeUnit.SECONDS);
      new CpuIntensiveTask().start();
   }

   static class CpuIntensiveTask extends Thread {
      @Override
      public void run() {
         while (true) {
            System.gc();
         }
      }
   }

   private static class PrintCpuUsage implements Runnable {
      final Sigar sigar = new Sigar();

      public void run() {
         try {
            final CpuPerc cpuPerc = sigar.getCpuPerc();
            System.out.println("Cpu usage: " + cpuPerc);
         } catch (SigarException e) {
            e.printStackTrace();  // TODO: Customise this generated block
         }
      }
   }

}
