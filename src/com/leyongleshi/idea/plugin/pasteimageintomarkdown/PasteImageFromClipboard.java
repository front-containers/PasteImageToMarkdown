package com.leyongleshi.idea.plugin.pasteimageintomarkdown;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.SystemIndependent;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;


public class PasteImageFromClipboard extends AnAction {

    private Map<Object, String> imagesFromClipboard;

    public PasteImageFromClipboard(Map<Object, String> imagesFromClipboard) {
        this.imagesFromClipboard = imagesFromClipboard;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {

        Editor ed = e.getData(PlatformDataKeys.EDITOR);
        if (ed == null) {
            return;
        }

        //读取存储路径
        String imageSaveLocationValue = PropertiesComponent.getInstance().getValue(Constants.IMAGE_SAVE_LOCATION);
        if (imageSaveLocationValue == null || imageSaveLocationValue.trim().length() == 0) {
            throw new RuntimeException("please fill infos in settings");
        }

        if (!"LOCAL".equalsIgnoreCase(imageSaveLocationValue) && !"QINIU".equalsIgnoreCase(imageSaveLocationValue) && !"ALIYUN".equalsIgnoreCase(imageSaveLocationValue)) {
            throw new RuntimeException("not support " + imageSaveLocationValue.toLowerCase());
        }

        if ("LOCAL".equalsIgnoreCase(imageSaveLocationValue)) {
            String localRelativeDirPath = PropertiesComponent.getInstance().getValue(Constants.LOCAL_RELATIVE_DIR_PATH);
            if (isEmpty(localRelativeDirPath)) {
                throw new RuntimeException("please set RELATIVE_DIRECTORY_PATH in settings");
            }

            //save image to disk
            @SystemIndependent String projectFilePath = ed.getProject().getBasePath();
            File imageSaveDir = new File(projectFilePath, localRelativeDirPath);
            if (!imageSaveDir.exists() || !imageSaveDir.isDirectory()) {
                if (!imageSaveDir.mkdirs()) {
                    throw new RuntimeException("cannot mkdir:" + imageSaveDir.getAbsolutePath());
                }
            }

            for (Map.Entry<Object, String> entry : imagesFromClipboard.entrySet()) {
                Object key = entry.getKey();
                String suffix = entry.getValue();
                File imgFile = new File(imageSaveDir, System.nanoTime() + suffix);

                if (key instanceof BufferedImage) {
                    BufferedImage bufferedImage = (BufferedImage) key;
                    ImageUtils.saveImage(bufferedImage, imgFile);
                } else if (key instanceof File) {
                    File file = (File) key;
                    try {
                        FileUtils.copyFile(file, imgFile);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                } else {
                    throw new RuntimeException("something wrong");
                }

                //refresh
                VirtualFile fileByPath = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(imgFile);
                assert fileByPath != null;

                //add file to vcs
                AbstractVcs usedVcs = ProjectLevelVcsManager.getInstance(ed.getProject()).getVcsFor(fileByPath);
                if (usedVcs != null && usedVcs.getCheckinEnvironment() != null) {
                    usedVcs.getCheckinEnvironment().scheduleUnversionedFilesForAddition(Collections.singletonList(fileByPath));
                }

                //calc the relative path between current editor file and imgFile
                Document currentDoc = FileEditorManager.getInstance(ed.getProject()).getSelectedTextEditor().getDocument();
                VirtualFile currentFile = FileDocumentManager.getInstance().getFile(currentDoc);
                File curDocument = new File(currentFile.getPath());
                String imgRelativeUrl = curDocument.getParentFile().toPath().relativize(imgFile.toPath()).toFile().toString().replace('\\', '/');

                //insert img url to md file
                insertImageElement(ed, imgRelativeUrl);
            }

            return;
        }

        if ("QINIU".equalsIgnoreCase(imageSaveLocationValue)) {
            String qiniuImgUrlPrefix = PropertiesComponent.getInstance().getValue(Constants.QINIU_IMG_URL_PREFIX);
            String qiniuBucketName = PropertiesComponent.getInstance().getValue(Constants.QINIU_BUCKET_NAME);
            String qiniuAccessKey = PropertiesComponent.getInstance().getValue(Constants.QINIU_ACCESS_KEY);
            String qiniuSecretKey = PropertiesComponent.getInstance().getValue(Constants.QINIU_SECRET_KEY);

            if (isEmpty(qiniuImgUrlPrefix)) {
                throw new RuntimeException("please set IMG_URL_PREFIX in settings");
            }
            if (isEmpty(qiniuBucketName)) {
                throw new RuntimeException("please set BUCKET_NAME in settings");
            }
            if (isEmpty(qiniuAccessKey)) {
                throw new RuntimeException("please set ACCESS_KEY in settings");
            }
            if (isEmpty(qiniuSecretKey)) {
                throw new RuntimeException("please set SECRET_KEY in settings");
            }

            if (!qiniuImgUrlPrefix.endsWith("/")) {
                qiniuImgUrlPrefix += "/";
            }
            QiniuHelper qiniuHelper = new QiniuHelper(qiniuAccessKey, qiniuSecretKey, qiniuBucketName, qiniuImgUrlPrefix, 3);

            for (Map.Entry<Object, String> entry : imagesFromClipboard.entrySet()) {
                Object key = entry.getKey();
                String suffix = entry.getValue();
                String imgUrl;
                if (key instanceof BufferedImage) {
                    BufferedImage bufferedImage = (BufferedImage) key;
                    imgUrl = qiniuHelper.upload(bufferedImage, genCdnFilePathAndName(suffix));
                } else if (key instanceof File) {
                    File file = (File) key;
                    imgUrl = qiniuHelper.upload(file, genCdnFilePathAndName(suffix));
                } else {
                    throw new RuntimeException("something wrong");
                }
                insertImageElement(ed, imgUrl);
            }
            return;
        }

        if ("ALIYUN".equalsIgnoreCase(imageSaveLocationValue)) {
            String aliyunAccessKeySecret = PropertiesComponent.getInstance().getValue(Constants.ALIYUN_ACCESS_KEY_SECRET);
            String aliyunEndPoint = PropertiesComponent.getInstance().getValue(Constants.ALIYUN_END_POINT);
            String aliyunAccessKeyId = PropertiesComponent.getInstance().getValue(Constants.ALIYUN_ACCESS_KEY_ID);
            String aliyunBucketName = PropertiesComponent.getInstance().getValue(Constants.ALIYUN_BUCKET_NAME);

            if (isEmpty(aliyunAccessKeySecret)) {
                throw new RuntimeException("please set ALIYUN_ACCESS_KEY_SECRET in settings");
            }
            if (isEmpty(aliyunEndPoint)) {
                throw new RuntimeException("please set ALIYUN_END_POINT in settings");
            }
            if (isEmpty(aliyunAccessKeyId)) {
                throw new RuntimeException("please set ALIYUN_ACCESS_KEY_ID in settings");
            }
            if (isEmpty(aliyunBucketName)) {
                throw new RuntimeException("please set ALIYUN_BUCKET_NAME in settings");
            }

            AliyunOSSHelper aliyunOSSHelper = new AliyunOSSHelper(aliyunEndPoint, aliyunAccessKeyId, aliyunAccessKeySecret, aliyunBucketName);
            for (Map.Entry<Object, String> entry : imagesFromClipboard.entrySet()) {
                Object key = entry.getKey();
                String suffix = entry.getValue();
                String imgUrl = "";
                if (key instanceof BufferedImage) {
                    imgUrl = aliyunOSSHelper.upload((BufferedImage) key, genCdnFilePathAndName(suffix));
                } else if (key instanceof File) {
                    File file = (File) key;
                    imgUrl = aliyunOSSHelper.upload(file,genCdnFilePathAndName(suffix));
                } else {
                    throw new RuntimeException("something wrong");
                }
                insertImageElement(ed, imgUrl);
            }

            return;
        }
    }

    private static String genCdnFilePathAndName(String suffix) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd/HH-mm-ss/");
        Date now = new Date();
        return "pasteimageintomarkdown/" + sdf.format(now) + System.nanoTime() + suffix;
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    private void insertImageElement(final @NotNull Editor editor, String imageurl) {
        String picUrl = "![](" + imageurl + ")\n";
        Runnable r = () -> EditorModificationUtil.insertStringAtCaret(editor, picUrl);
        WriteCommandAction.runWriteCommandAction(editor.getProject(), r);
    }

}
