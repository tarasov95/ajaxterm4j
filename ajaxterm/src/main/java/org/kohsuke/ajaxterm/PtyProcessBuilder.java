package org.kohsuke.ajaxterm;

import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.kohsuke.ajaxterm.CLibrary.*;
import static org.kohsuke.ajaxterm.UtilLibrary.LIBUTIL;

/**
 * {@link ProcessBuilder} for launching a child process with pseudo-terminal.
 *
 * <p>
 * A pseudo-terminal is a special kind of pipe, so when we launch a new child process
 * with a {@link Terminal}, a special kind of fork needs to be used, hence this class
 * and not the standard {@link ProcessBuilder}.
 *
 * @author Kohsuke Kawaguchi
 */
public class PtyProcessBuilder {
    private List<String> commands = new ArrayList<String>();
    private File pwd;
    private Map<String,String> environment = new HashMap<String, String>();
    private int width, height;


    public PtyProcessBuilder screen(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public PtyProcessBuilder commands(List<String> cmds) {
        this.commands.addAll(cmds);
        return this;
    }

    public PtyProcessBuilder commands(String... cmds) {
        return commands(Arrays.asList(cmds));
    }

    public List<String> commands() {
        return commands();
    }

    public PtyProcessBuilder pwd(File pwd) {
        this.pwd = pwd;
        return this;
    }

    public PtyProcessBuilder env(String name, String value) {
        this.environment.put(name,value);
        return this;
    }

    public PtyProcessBuilder envs(Map<String,String> envs) {
        this.environment.putAll(envs);
        return this;
    }

    public Map<String,String> envs() {
        return this.environment;
    }

    public Process fork() {
        if(commands.size()==0)
            throw new IllegalArgumentException("No command line arguments");

        // make execv call to force classloading
        // once we fork, the child process cannot load more classes reliably.
        LIBC.execv("-",new String[]{"-","-"});
        for( int i=LIBC.getdtablesize()-1; i>0; i-- ) {
            LIBC.fcntl(1,F_GETFD,0);
        }

        // capture everything before the fork since JVM will be unstable between fork & exec
        String program = commands.get(0);
        String[] args  = commands.toArray(new String[commands.size()]);
        String pwd = this.pwd==null ? null : this.pwd.getAbsolutePath();
        this.environment.put("TERM","linux");
        String[] envs = new String[environment.size()*2];
        int idx=0;
        for (Map.Entry<String,String> e :environment.entrySet()) {
            envs[idx++] = e.getKey();
            envs[idx++] = e.getValue();
        }


        IntByReference pty = new IntByReference();
        final int pid = LIBUTIL.forkpty(pty, null, null, null);
        if(pid==0) {
            // on child process
            LIBC.setsid();
            for( int i=LIBC.getdtablesize()-1; i>=3; i-- ) {
                LIBC.fcntl(i, F_SETFD,LIBC.fcntl(i, F_GETFD,0)|FD_CLOEXEC);
            }

            if (pwd!=null)
                LIBC.chdir(pwd);

            // environment variable overrides
            for (int i=0; i<envs.length; i+=2) {
                if (envs[i+1]==null)
                    LIBC.unsetenv(envs[i]);
                else
                    LIBC.setenv(envs[i],envs[i+1],1);
            }

            LIBC.execv(program,args);
        }

        Memory struct = new Memory(8);  // 4 unsigned shorts
        struct.setShort(0,(short)height);
        struct.setShort(2,(short)width);
        struct.setInt(4,0);
        if (LIBC.ioctl(pty.getValue(),TIOCSWINSZ,struct)!=0)
            throw new IllegalStateException("Failed to ioctl(TIOCSWINSZ)");

        FileDescriptor fileDescriptor = createFileDescriptor(pty.getValue());
        final InputStream in = new FileInputStream(fileDescriptor);
        final OutputStream out = new FileOutputStream(fileDescriptor);

        return new Process() {
            private Integer exitCode;

            @Override
            public InputStream getErrorStream() {
                return null;    // terminal only have two ends, read+write
            }

            @Override
            public OutputStream getOutputStream() {
                return out;
            }

            @Override
            public InputStream getInputStream() {
                return in;
            }

            @Override
            public int exitValue() {
                if (exitCode==null) {
                    waitpid(WNOHANG);
                }
                if (exitCode==null)
                    throw new IllegalThreadStateException();
                return exitCode;
            }

            @Override
            public int waitFor() throws InterruptedException {
                if (exitCode==null) {
                    waitpid(0);
                }
                return exitCode;

            }

            private void waitpid(int options) {
                IntByReference status = new IntByReference();
                boolean isDead = LIBC.waitpid(pid, status, options) > 0;
                if (isDead) {
                    int x = status.getValue();
                    if ((x&0x7F)!=0)    exitCode=128+(x&0x7F);
                    exitCode = (x>>8)&0xFF;
                }
            }

            @Override
            public void destroy() {
                if (exitCode==null)
                    LIBC.kill(pid,15/*SIGTERM*/);
            }
        };
    }

    private static FileDescriptor createFileDescriptor(int v) {
        try {
            FileDescriptor fd = new FileDescriptor();
            Field f = FileDescriptor.class.getDeclaredField("fd");
            f.setAccessible(true);
            f.set(fd,v);
            return fd;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

}