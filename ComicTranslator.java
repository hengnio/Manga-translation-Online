import spark.Spark;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class ComicTranslator {
    private static final String UPLOAD_DIR = "uploads";
    private static final String TRANSLATION_FILE = "translations.json";
    private static final Map<String, Map<String, List<TranslationArea>>> translations = new ConcurrentHashMap<>();
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024;
    private static final Gson gson = new Gson();
    private static final String CHANGELOG_FILE = "changelog.txt";

    static class TranslationArea {
        int x, y, width, height;
        String original;
        String translation;
    }

    public static void main(String[] args) {
        loadTranslations();
        configureServer();
        createUploadDirectory();
        setupRoutes();
    }

    private static void configureServer() {
        Spark.port(8080);
        Spark.staticFiles.externalLocation(UPLOAD_DIR);
        Spark.exception(Exception.class, (ex, req, res) -> {
            ex.printStackTrace();
            res.status(500);
            res.body("服务器错误: " + ex.getMessage());
        });
    }

    private static void createUploadDirectory() {
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            throw new RuntimeException("无法创建上传目录: " + e.getMessage());
        }
    }

    private static void setupRoutes() {
        homeRoute();
        groupRoute();
        uploadRoute();
        newGroupRoute();
        deleteGroupRoute();
        renameGroupRoute();
        editRoute();
        saveRoute();
        exportRoutes();
    }

    private static void loadTranslations() {
        try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(Paths.get(UPLOAD_DIR))) {
            for (Path groupPath : groupStream) {
                if (Files.isDirectory(groupPath)) {
                    String groupName = groupPath.getFileName().toString();
                    Path translationFile = groupPath.resolve(TRANSLATION_FILE);
                    
                    if (Files.exists(translationFile)) {
                        String json = new String(Files.readAllBytes(translationFile));
                        Map<String, List<TranslationArea>> groupTranslations = gson.fromJson(json,
                            new TypeToken<ConcurrentHashMap<String, List<TranslationArea>>>(){}.getType());
                        translations.put(groupName, groupTranslations);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("无历史翻译数据可加载");
        }
    }

    private static void saveTranslationsToFile(String group) {
        Path groupPath = Paths.get(UPLOAD_DIR, group);
        Path translationFile = groupPath.resolve(TRANSLATION_FILE);
        
        try {
            if (!Files.exists(groupPath)) {
                Files.createDirectories(groupPath);
            }
            String json = gson.toJson(translations.get(group));
            Files.write(translationFile, json.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("保存翻译数据失败: " + e.getMessage());
        }
    }

    private static void homeRoute() {
        Spark.get("/", (req, res) -> {
            StringBuilder html = new StringBuilder()
                .append("<html><head><title>漫画翻译工具</title>")
                .append("<style>")
                .append(".changelog { margin-top: 30px; padding: 20px; background: #f1f8ff; border-radius: 5px; }")
                .append(".changelog h2 { color: #0366d6; }")
                .append(".changelog-content { white-space: pre-wrap; }")
                .append("</style></head>")
                .append("<style>body { font-family: Arial; max-width: 1200px; margin: 20px auto; }")
                .append(".group-list { background: #f8f9fa; padding: 20px; border-radius: 5px; }</style></head>")
                .append("<body><h1>漫画翻译工具</h1><div class='group-list'><h2>分组列表：</h2>");

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(UPLOAD_DIR))) {
                for (Path path : stream) {
                    if (Files.isDirectory(path)) {
                        String groupName = path.getFileName().toString();
                        html.append("<div style='margin:10px; padding:10px; background:#fff;'>")
                            .append("<a href='/group/").append(groupName).append("'>").append(groupName).append("</a>")
                            .append(" <a href='/export/").append(groupName).append("' style='color:green;'>导出本组翻译</a>")
                            .append("<form method='post' action='/rename-group/").append(groupName).append("' style='display:inline; margin-left:10px;'>")
                            .append("<input type='text' name='newName' placeholder='新名称' required style='padding:3px;'>")
                            .append("<button type='submit' style='margin-left:5px; padding:3px 8px;'>重命名</button>")
                            .append("</form>")
                            .append("<form method='post' action='/delete-group/").append(groupName).append("' style='display:inline; margin-left:10px;'>")
                            .append("<button type='submit' onclick=\"return confirm('确定删除分组 ").append(groupName).append(" 吗？')\" style='background:#ff4444; color:white; padding:3px 8px;'>删除</button>")
                            .append("</form>")
                            .append("</div>");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            html.append("<div style='margin-top:20px;'><h3>新建分组</h3>")
                .append("<form method='post' action='/new-group'>")
                .append("<input type='text' name='groupname' placeholder='分组名称' required>")
                .append("<button type='submit'>创建分组</button></form></div>")
                .append("<a href='/export' style='display:block; margin-top:20px; color:green;'>导出全部翻译</a>")
                .append("</div></body></html>");
            html.append("<div class='changelog'>")
                .append("<h2>更新历程</h2>")
                .append("<div class='changelog-content'>")
                .append(loadChangelog())  // 加载日志内容
                .append("</div></div>");

            html.append("</div></body></html>");

            return html.toString();
        });
    }

    private static void groupRoute() {
        Spark.get("/group/:group", (req, res) -> {
            String group = req.params(":group");
            Path groupPath = Paths.get(UPLOAD_DIR, group);
            
            if (!Files.exists(groupPath)) {
                res.status(404);
                return "分组不存在";
            }

            StringBuilder html = new StringBuilder()
                .append("<html><head><title>分组: ").append(group).append("</title>")
                .append("<style>.file-item { margin:10px; padding:10px; background:#fff; }</style></head>")
                .append("<body><h1>分组: ").append(group).append("</h1><a href='/'>返回首页</a><div style='margin-top:20px;'>");

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(groupPath)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file) && !file.getFileName().toString().equals(TRANSLATION_FILE)) {
                        String filename = file.getFileName().toString();
                        html.append("<div class='file-item'>")
                            .append("<a href='/edit/").append(group).append("/").append(filename).append("'>").append(filename).append("</a>")
                            .append("</div>");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            html.append("<h3 style='margin-top:20px;'>上传文件到本组</h3>")
                .append("<form method='post' action='/upload/").append(group).append("' enctype='multipart/form-data'>")
                .append("<input type='file' name='files' multiple accept='image/*' required>")
                .append("<button type='submit'>上传文件</button></form></div></body></html>");

            return html.toString();
        });
    }

    private static void uploadRoute() {
        Spark.post("/upload/:group", (req, res) -> {
            String group = req.params(":group");
            Path groupPath = Paths.get(UPLOAD_DIR, group);
            
            if (!Files.exists(groupPath)) {
                res.status(404);
                return "分组不存在";
            }

            // 增强配置：设置临时目录和更合理的缓冲区
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            MultipartConfigElement multipartConfigElement = new MultipartConfigElement(
                tempDir.getAbsolutePath(),  // 指定临时目录
                MAX_FILE_SIZE,              // 最大文件大小
                MAX_FILE_SIZE * 2,          // 最大请求大小
                1024 * 1024                 // 内存缓冲大小
            );
        
            req.attribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);
        
            try {
                Collection<Part> parts = req.raw().getParts();
                for (Part part : parts) {
                    if ("files".equals(part.getName())) {
                        String filename = Paths.get(part.getSubmittedFileName()).getFileName().toString();
                        // 使用try-with-resources确保流关闭
                        try (InputStream in = part.getInputStream()) {
                            Files.copy(in, groupPath.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                res.redirect("/group/" + group);
                return null;
            } catch (Exception e) {
                // 增强错误日志
                System.err.println("上传失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
                res.status(500);
                return "上传失败: " + (e.getMessage().length() > 100 ? e.getMessage().substring(0, 100) + "..." : e.getMessage());
            }
        });
    }

    private static void newGroupRoute() {
        Spark.post("/new-group", (req, res) -> {
            String groupName = req.queryParams("groupname");
            try {
                Path groupPath = Paths.get(UPLOAD_DIR, groupName);

                if (Files.exists(groupPath)) {
                    res.status(400);
                    return "分组已存在";
                }

                Files.createDirectory(groupPath);
                Files.createFile(groupPath.resolve(TRANSLATION_FILE));
                res.redirect("/");
                return null;
            } catch (InvalidPathException e) {
                res.status(400);
                return "非法分组名称: " + e.getMessage();
            } catch (IOException e) {
                System.err.println("创建分组失败: " + e.getMessage());
                e.printStackTrace();
                res.status(500);
                return "创建分组失败: " + e.getMessage();
            }
        });
    }

    private static void deleteGroupRoute() {
        Spark.post("/delete-group/:group", (req, res) -> {
            String group = req.params(":group");
            Path groupPath = Paths.get(UPLOAD_DIR, group);
        
            if (!Files.exists(groupPath)) {
                res.status(404);
                return "分组不存在"; // 直接返回错误信息
            }

            try {
                // 递归删除目录
                Files.walk(groupPath)
                     .sorted(Comparator.reverseOrder())
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             throw new RuntimeException("删除失败: " + path, e);
                         }
                     });
                translations.remove(group);
                res.redirect("/");
                return null; // 添加显式返回
            } catch (Exception e) {
                res.status(500);
                return "删除分组失败: " + e.getMessage();
            }
        });
    }

    private static void renameGroupRoute() {
        Spark.post("/rename-group/:oldGroup", (req, res) -> {
            String oldGroup = req.params(":oldGroup");
            String newGroup = req.queryParams("newName");
        
            if (newGroup == null || newGroup.trim().isEmpty()) {
                res.status(400);
                return "新分组名称不能为空";
            }

            Path oldPath = Paths.get(UPLOAD_DIR, oldGroup);
            Path newPath = Paths.get(UPLOAD_DIR, newGroup);
        
            if (!Files.exists(oldPath)) {
                res.status(404);
                return "原分组不存在";
            }
        
            if (Files.exists(newPath)) {
                res.status(400);
                return "新分组名称已存在";
            }

            try {
                // 移动目录
                Files.move(oldPath, newPath);
                // 更新内存数据
                Map<String, List<TranslationArea>> groupData = translations.remove(oldGroup);
                if (groupData != null) {
                    translations.put(newGroup, groupData);
                    saveTranslationsToFile(newGroup);
                }
                res.redirect("/");
                return null; // 添加显式返回
            } catch (IOException e) {
                res.status(500);
                return "重命名失败: " + e.getMessage();
            }
        });
    }

    private static void editRoute() {
        Spark.get("/edit/:group/:filename", (req, res) -> {
            String group = req.params(":group");
            String filename = req.params(":filename");
            
            List<TranslationArea> areas = translations
                .getOrDefault(group, new ConcurrentHashMap<>())
                .getOrDefault(filename, new ArrayList<>());

            String jsonData = gson.toJson(areas);
            
            return "<!DOCTYPE html><html><head>" 
                + "<script src='https://cdnjs.cloudflare.com/ajax/libs/fabric.js/4.5.0/fabric.min.js'></script>"
                + "<style>"
                + "#canvas-container { position: relative; margin: 20px 0; }"
                + "#main-canvas { border: 2px solid #ddd; }"
                + ".toolbar { margin: 10px 0; }"
                + "button { padding: 8px 15px; margin-right: 10px; cursor: pointer; }"
                + ".translation-box { margin: 10px 0; padding: 10px; border: 1px solid #ddd; }"
                + "</style></head><body>"
                + "<h1>编辑翻译：" + filename + "</h1>"
               + "<div class='toolbar'>"
                + "<button onclick='enableRect()'>新建标注区域</button>"
                + "<button onclick='saveTranslations()'>保存全部翻译</button>"
                + "<a href='/group/" + group + "' style='padding: 8px 15px; background: #6c757d; color: white; text-decoration: none;'>返回分组</a>"
                + "</div>"
                + "<div id='canvas-container'>"
                + "<canvas id='main-canvas'></canvas>"
                + "</div>"
                + "<script>"
                + "let canvas = new fabric.Canvas('main-canvas', { selection: false });"
                + "let currentRect = null;"
                + "let startX = 0, startY = 0;"
            
                + "let img = new Image();\n"
                + "img.src = '/" + group + "/" + filename + "';\n"
                + "img.onload = () => {\n"
                + "  canvas.setWidth(img.width);\n"
                + "  canvas.setHeight(img.height);\n"
                + "  canvas.setBackgroundImage(img.src, canvas.renderAll.bind(canvas));\n"
                + "};\n"

                + "let areas = " + jsonData + ";\n"
                + "areas.forEach((area, index) => {\n"
                + "  let rectId = 'rect_' + Date.now() + Math.random().toString(36).substr(2, 9);\n"
                + "  let rect = new fabric.Rect({\n"
                + "    id: rectId,\n"
                + "    left: area.x,\n"
                + "    top: area.y,\n"
                + "    width: area.width,\n"
                + "    height: area.height,\n"
                + "    fill: 'rgba(255,0,0,0.3)',\n"
                + "    stroke: 'red',\n"
                + "    strokeWidth: 2,\n"
                + "    hasControls: true,\n"
                + "    data: area\n"
                + "  });\n"
                + "  canvas.add(rect);\n"
                + "  showTranslationBox(rect, area);\n"
                + "});\n"

                + "function enableRect() {\n"
                + "  canvas.isDrawingMode = false;\n"
                + "  canvas.on('mouse:down', startRect);\n"
                + "  canvas.on('mouse:move', drawRect);\n"
                + "  canvas.on('mouse:up', finishRect);\n"
                + "}\n"

                + "function startRect(e) {\n"
                + "  startX = e.pointer.x;\n"
                + "  startY = e.pointer.y;\n"
                + "  let rectId = 'rect_' + Date.now() + Math.random().toString(36).substr(2, 9);\n"
                + "  currentRect = new fabric.Rect({\n"
                + "    id: rectId,\n"
                + "    left: startX,\n"
                + "    top: startY,\n"
                + "    width: 0,\n"
                + "    height: 0,\n"
                + "    fill: 'rgba(0,255,0,0.3)',\n"
                + "    stroke: 'green',\n"
                + "    strokeWidth: 2,\n"
                + "    selectable: true\n"
                + "  });\n"
                + "  canvas.add(currentRect);\n"
                + "}\n"

                + "function drawRect(e) {\n"
                + "  if (!currentRect) return;\n"
                + "  let x = e.pointer.x;\n"
                + "  let y = e.pointer.y;\n"
                + "  currentRect.set({\n"
                + "    width: x - startX,\n"
                + "    height: y - startY\n"
                + "  });\n"
                + "  canvas.renderAll();\n"
                + "}\n"

                + "function finishRect() {\n"
                + "  currentRect.setCoords();\n"
                + "  showTranslationBox(currentRect, { original: '', translation: '' });\n" // 新增初始化空数据
                + "  currentRect = null;\n"
                + "}\n"

                + "function showTranslationBox(rect, areaData) {\n"
                + "  let box = document.createElement('div');\n"
                + "  box.className = 'translation-box';\n"
                + "  box.dataset.rectId = rect.id;\n"
                + "  box.innerHTML = `\n"
                + "    <h3>翻译区域 #${canvas.getObjects().length}</h3>\n"
                + "    <input type='text' placeholder='原文' \n"
                + "           value='${areaData?.original || ''}'\n"
                + "           style='width: 200px; margin-right: 10px;'>\n"
                + "    <input type='text' placeholder='翻译内容' \n"
                + "           value='${areaData?.translation || ''}'\n"
                + "           style='width: 300px;'>\n"
                + "    <button onclick='deleteTranslationBox(this)'>删除</button>\n"
                + "  `;\n"
                + "  document.body.appendChild(box);\n"
                + "}\n"

                + "function deleteTranslationBox(button) {\n"
                + "  let box = button.parentElement;\n"
                + "  let rectId = box.dataset.rectId;\n"
                + "  let rect = canvas.getObjects().find(obj => obj.id === rectId);\n"
                + "  if (rect) canvas.remove(rect);\n"
                + "  box.remove();\n"
                + "}\n"

                + "function saveTranslations() {\n"
                + "  let areas = [];\n"
                + "  canvas.getObjects().forEach((obj) => {\n"
                + "    if (obj instanceof fabric.Rect) {\n"
                + "      let box = document.querySelector(`.translation-box[data-rect-id=\"${obj.id}\"]`);\n"
                + "      if (!box) return;\n"
                + "      let inputs = box.getElementsByTagName('input');\n"
                + "      areas.push({\n"
                + "        x: Math.round(obj.left),\n"
                + "        y: Math.round(obj.top),\n"
                + "        width: Math.round(obj.width),\n"
                + "        height: Math.round(obj.height),\n"
                + "        original: inputs[0].value,\n"
                + "        translation: inputs[1].value\n"
                + "      });\n"
                + "    }\n"
                + "  });\n"
            
                + "  fetch('/save/" + group + "/" + filename + "', {\n"
                + "    method: 'POST',\n"
                + "    headers: { 'Content-Type': 'application/json' },\n"
                + "    body: JSON.stringify(areas)\n"
                + "  }).then(response => {\n"
                + "    if (response.ok) alert('保存成功！');\n"
                + "    else alert('保存失败！');\n"
                + "  });\n"
                + "}\n"
                + "</script></body></html>";
        });
    }

    private static void saveRoute() {
        Spark.post("/save/:group/:filename", (req, res) -> {
            String group = req.params(":group");
            String filename = req.params(":filename");
            
            try {
                TranslationArea[] areas = gson.fromJson(req.body(), TranslationArea[].class);
                translations
                    .computeIfAbsent(group, k -> new ConcurrentHashMap<>())
                    .put(filename, new ArrayList<>(Arrays.asList(areas)));
                
                saveTranslationsToFile(group);
                res.status(200);
                return "OK";
            } catch (Exception e) {
                res.status(500);
                return "保存失败: " + e.getMessage();
            }
        });
    }

    private static void exportRoutes() {
        Spark.get("/export", (req, res) -> {
            StringBuilder output = new StringBuilder();
            translations.forEach((group, files) -> {
                output.append("=== 分组 [").append(group).append("] ===\n");
                files.forEach((filename, areas) -> {
                    output.append("--- 文件: ").append(filename).append(" ---\n");
                    appendTranslations(output, areas);
                });
            });
            return prepareExport(res, output);
        });

        Spark.get("/export/:group", (req, res) -> {
            String group = req.params(":group");
            if (!translations.containsKey(group)) {
                res.status(404);
                return "分组不存在";
            }

            StringBuilder output = new StringBuilder();
            output.append("=== 分组 [").append(group).append("] ===\n");
            translations.get(group).forEach((filename, areas) -> {
                output.append("--- 文件: ").append(filename).append(" ---\n");
                appendTranslations(output, areas);
            });
            return prepareExport(res, output);
        });
    }

    private static void appendTranslations(StringBuilder output, List<TranslationArea> areas) {
        for (int i = 0; i < areas.size(); i++) {
            TranslationArea area = areas.get(i);
            output.append(String.format("区域 %d [位置: %dpx, %dpx 尺寸: %dx%d]\n", 
                i+1, area.x, area.y, area.width, area.height));
            output.append("原文: ").append(area.original);
            output.append("\n翻译: ").append(area.translation).append("\n\n");
        }
    }

    private static String prepareExport(spark.Response res, StringBuilder output) {
        res.header("Content-Disposition", "attachment; filename=translations.txt");
        res.type("text/plain");
        return output.toString();
    }

    private static String loadChangelog() {
        try {
            return new String(Files.readAllBytes(Paths.get(CHANGELOG_FILE)));
        } catch (IOException e) {
            System.err.println("加载更新日志失败: " + e.getMessage());
            return "暂无更新日志";
        }
    }
} // 类结束大括号