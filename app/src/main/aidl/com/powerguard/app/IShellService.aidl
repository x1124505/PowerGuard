package com.powerguard.app;

/**
 * 运行在 Shizuku 提供的 shell 进程中的服务接口。
 * 因为进程本身具备 shell(uid 2000) 权限，所以这里执行命令等价于 adb shell。
 */
interface IShellService {
    /**
     * 执行 shell 命令，返回 "exitCode|stdout|stderr"
     */
    String exec(String command);
}
