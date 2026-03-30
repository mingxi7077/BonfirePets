# BonfirePets

`BonfirePets` is the 5.0 replacement baseline for `MCPets + ModelEngine`.

Current scope:

- imports legacy `MCPets` pets, categories, menu assets, player data, and optional MySQL rows into a standalone SQLite database
- validates legacy MythicMobs / BetterModel compatibility before import
- stores rollback snapshots and migration records for cold-start rollback
- exposes `/bpet import`, `/bpet validate`, `/bpet rollback`, `/bpet give`, `/bpet debug mount`, and `/bpet debug tracker`

Build:

```powershell
& 'C:\Program Files\Zulu\zulu-21\bin\java.exe' `
  -classpath '.mvn\wrapper\maven-wrapper.jar' `
  '-Dmaven.multiModuleProjectDirectory=E:\Minecraft\12121\purpur第四版\二、插件开发区\源码工程\BonfirePets' `
  org.apache.maven.wrapper.MavenWrapperMain -q -DskipTests package
```

5.0 baseline sync:

```powershell
powershell -ExecutionPolicy Bypass -File `
  'E:\Minecraft\12121\purpur第四版\一、服务器核心区\5.0正式服核心\工具\Prepare-BetterModelBaseline.ps1'
```

Known runtime gap:

- BetterModel jar still needs to be provided manually to the 5.0 `server/plugins` directory before live runtime validation.
