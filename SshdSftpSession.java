import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionException;

import java.io.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public class SshdSftpSession implements Session<SftpClient.DirEntry> {

    private final SftpClient sftpClient;
    private final ClientSession clientSession;
    private boolean open = true;

    public SshdSftpSession(SftpClient sftpClient, ClientSession clientSession) {
        this.sftpClient = sftpClient;
        this.clientSession = clientSession;
    }

    //-------------------------
    // 核心方法实现
    //-------------------------

    @Override
    public boolean remove(String path) throws SessionException {
        checkOpen();
        try {
            sftpClient.remove(path);
            return true;
        } catch (IOException e) {
            throw new SessionException("Failed to delete file: " + path, e);
        }
    }

    @Override
    public SftpClient.DirEntry[] list(String path) throws SessionException {
        checkOpen();
        try {
            return sftpClient.readDir(path).toArray(new SftpClient.DirEntry[0]);
        } catch (IOException e) {
            throw new SessionException("Failed to list directory: " + path, e);
        }
    }

    @Override
    public void read(String source, OutputStream outputStream) throws SessionException {
        checkOpen();
        try (InputStream inputStream = sftpClient.read(source)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new SessionException("Failed to read file: " + source, e);
        }
    }

    @Override
    public void write(InputStream inputStream, String destination) throws SessionException {
        checkOpen();
        try (OutputStream outputStream = sftpClient.write(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new SessionException("Failed to write file: " + destination, e);
        }
    }

    @Override
    public boolean mkdir(String directory) throws SessionException {
        checkOpen();
        try {
            sftpClient.mkdir(directory);
            return true;
        } catch (IOException e) {
            throw new SessionException("Failed to create directory: " + directory, e);
        }
    }

    @Override
    public boolean rmdir(String directory) throws SessionException {
        checkOpen();
        try {
            sftpClient.rmdir(directory);
            return true;
        } catch (IOException e) {
            throw new SessionException("Failed to remove directory: " + directory, e);
        }
    }

    @Override
    public boolean rename(String pathFrom, String pathTo) throws SessionException {
        checkOpen();
        try {
            sftpClient.rename(pathFrom, pathTo);
            return true;
        } catch (IOException e) {
            throw new SessionException("Failed to rename from " + pathFrom + " to " + pathTo, e);
        }
    }

    @Override
    public boolean exists(String path) throws SessionException {
        checkOpen();
        try {
            return sftpClient.stat(path) != null;
        } catch (IOException e) {
            return false; // 文件不存在时 stat 会抛出异常
        }
    }

    //-------------------------
    // 辅助方法实现
    //-------------------------

    @Override
    public String[] listNames(String path) throws SessionException {
        SftpClient.DirEntry[] entries = list(path);
        return Arrays.stream(entries)
                .map(SftpClient.DirEntry::getFilename)
                .toArray(String[]::new);
    }

    @Override
    public InputStream readRaw(String source) throws SessionException {
        checkOpen();
        try {
            return sftpClient.read(source);
        } catch (IOException e) {
            throw new SessionException("Failed to open input stream for: " + source, e);
        }
    }

    @Override
    public OutputStream writeRaw(String destination) throws SessionException {
        checkOpen();
        try {
            return sftpClient.write(destination);
        } catch (IOException e) {
            throw new SessionException("Failed to open output stream for: " + destination, e);
        }
    }

    @Override
    public boolean append(InputStream inputStream, String destination) throws SessionException {
        checkOpen();
        try (OutputStream outputStream = sftpClient.append(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return true;
        } catch (IOException e) {
            throw new SessionException("Failed to append to file: " + destination, e);
        }
    }

    @Override
    public void close() {
        try {
            if (sftpClient != null) {
                sftpClient.close();
            }
            if (clientSession != null) {
                clientSession.close();
            }
            open = false;
        } catch (IOException e) {
            // 静默关闭异常
        }
    }

    @Override
    public boolean isOpen() {
        return open && clientSession.isOpen();
    }

    //-------------------------
    // 扩展功能实现
    //-------------------------

    /**
     * 执行 SSH 命令（非 SFTP 操作）
     * @param command 要执行的命令
     * @return 命令输出结果
     */
    public String executeCommand(String command) throws SessionException {
        checkOpen();
        try (ClientChannel channel = clientSession.createExecChannel(command)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            channel.setOut(output);
            channel.open().verify();
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 30_000L);
            return output.toString();
        } catch (IOException e) {
            throw new SessionException("Failed to execute command: " + command, e);
        }
    }

    /**
     * 设置文件权限
     * @param path 文件路径
     * @param permissions 权限集合
     */
    public void setPermissions(String path, Set<PosixFilePermission> permissions) throws SessionException {
        checkOpen();
        try {
            int mode = convertToMode(permissions);
            sftpClient.setStat(path, new SftpClient.Attributes().permissions(mode));
        } catch (IOException e) {
            throw new SessionException("Failed to set permissions for: " + path, e);
        }
    }

    //-------------------------
    // 私有工具方法
    //-------------------------

    private void checkOpen() {
        if (!isOpen()) {
            throw new SessionException("Session is closed");
        }
    }

    /**
     * 将 Posix 权限转换为数字模式
     */
    private static int convertToMode(Set<PosixFilePermission> permissions) {
        return permissions.stream()
                .mapToInt(p -> {
                    return switch (p) {
                        case OWNER_READ -> 0400;
                        case OWNER_WRITE -> 0200;
                        case OWNER_EXECUTE -> 0100;
                        case GROUP_READ -> 0040;
                        case GROUP_WRITE -> 0020;
                        case GROUP_EXECUTE -> 0010;
                        case OTHERS_READ -> 0004;
                        case OTHERS_WRITE -> 0002;
                        case OTHERS_EXECUTE -> 0001;
                        default -> 0;
                    };
                })
                .sum();
    }
}