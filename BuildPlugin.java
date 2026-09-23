import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** Dependency-free compiler/package launcher. Run with JDK 21, never business JDK 8. */
public final class BuildPlugin {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) throw new IllegalStateException("Plugin build requires JDK 21");
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Usage: java BuildPlugin.java test|build IDEA_SDK [test-group]");
        boolean test = args[0].equals("test");
        if (!test && !args[0].equals("build")) throw new IllegalArgumentException("Unknown task");
        if (test && (args.length != 3 || !Set.of("core","worktree","bundle","transport","cache","large","all").contains(args[2]))) {
            throw new IllegalArgumentException("Specify test group: core|worktree|bundle|transport|cache|large|all");
        }
        if (!test && args.length != 2) throw new IllegalArgumentException("Build does not accept a test group");
        Path root=Path.of("").toAbsolutePath(), sdk=Path.of(args[1]);
        Files.createDirectories(root.resolve(".build"));
        Path output=Files.createTempDirectory(root.resolve(".build"),test?"test-":"release-");
        Path classes=Files.createDirectory(output.resolve("classes"));
        List<String> options=new ArrayList<>(List.of("--release","21","-encoding","UTF-8","-d",classes.toString()));
        if(!test){
            if(!Files.readString(sdk.resolve("build.txt")).strip().equals("IC-251.26094.121"))throw new IOException("Release requires IC-251.26094.121 SDK");
            try(var paths=Files.list(sdk.resolve("lib"))){options.addAll(List.of("-classpath",String.join(File.pathSeparator,paths.filter(p->p.toString().endsWith(".jar")).map(Path::toString).toList())));}
        }
        Path source=root.resolve("src/main/java/dev/codingtransfer"+(test?"/core":""));
        try(var paths=Files.walk(source)){options.addAll(paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).toList());}
        if(test)try(var paths=Files.walk(root.resolve("src/test/java"))){options.addAll(paths.filter(p->p.toString().endsWith(".java")).map(Path::toString).toList());}
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,options.toArray(String[]::new))!=0)throw new IOException("Compilation failed");
        if(test){
            int code=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-Deverysync.test.logs="+output,"-ea","-cp",classes.toString(),"dev.codingtransfer.core.TestRunner",args[2]).inheritIO().start().waitFor();
            if(code!=0)throw new IOException("Tests failed");return;
        }
        Path resources=root.resolve("src/main/resources");
        try(var paths=Files.walk(resources)){for(Path file:paths.filter(Files::isRegularFile).toList()){Path target=classes.resolve(resources.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);}}
        Path jar=output.resolve("coding-transfer.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){
            for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){
                JarEntry entry=new JarEntry(classes.relativize(file).toString().replace('\\','/'));entry.setTime(0);out.putNextEntry(entry);Files.copy(file,out);out.closeEntry();
            }
        }
        Path zip=output.resolve("every-sync-4.3.4.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){ZipEntry entry=new ZipEntry("coding-transfer/lib/coding-transfer.jar");entry.setTime(0);out.putNextEntry(entry);Files.copy(jar,out);out.closeEntry();}
        Path dist=Files.createDirectories(root.resolve("dist")).resolve(zip.getFileName());Files.copy(zip,dist,StandardCopyOption.REPLACE_EXISTING);
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dist)));
        Files.writeString(dist.resolveSibling(dist.getFileName()+".sha256"),sha+"  "+dist.getFileName()+"\n",StandardCharsets.UTF_8);
        System.out.println("Plugin ZIP: "+dist+"\nSHA-256: "+sha+"\nSDK: IC-251.26094.121; target 251.26094.121–253.*");
    }
}
