package pandaq.com.gifemoticon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.Xml;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import pandaq.com.gifemoticon.gif.AnimatedGifDrawable;

/**
 * Created by huxinyu on 2017/10/19 0019.
 * description ：emoji 表情管理类
 */

public class EmoticonManager {

    private static String EMOT_DIR = "emoticons";
    private static String SOUCRE_DIR = "source";
    private static String STICKER_PATH = null; //默认路径在 /data/data/包名/files/sticker 下
    private int CACHE_MAX_SIZE = 1024;
    private Pattern mPattern;
    @SuppressLint("StaticFieldLeak")
    private static Context mContext;
    private String mConfigName = "emoji.xml";
    private static final List<ImageEntry> mDefaultEntries = new ArrayList<>();
    private static final Map<String, ImageEntry> mText2Entry = new HashMap<>();
    private static LruCache<String, Bitmap> mDrawableCache;
    private LruCache<String, AnimatedGifDrawable> mGifDrawableCache;
    @SuppressLint("StaticFieldLeak")
    private static EmoticonManager mEmoticonManager;
    private static IImageLoader mIImageLoader;

    private EmoticonManager init() {
        if (STICKER_PATH == null) {
            STICKER_PATH = new File(mContext.getFilesDir(), "sticker").getAbsolutePath();
        }
        load(mContext, EMOT_DIR + mConfigName);
        mPattern = makePattern();
        mDrawableCache = new LruCache<String, Bitmap>(CACHE_MAX_SIZE) {
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != newValue)
                    oldValue.recycle();
            }
        };
        mGifDrawableCache = new LruCache<String, AnimatedGifDrawable>(CACHE_MAX_SIZE) {
            @Override
            protected void entryRemoved(boolean evicted, String key, AnimatedGifDrawable oldValue, AnimatedGifDrawable newValue) {
                if (oldValue != newValue)
                    oldValue.setCallback(null);
            }
        };
        return this;
    }

    public static String getStickerPath() {
        return STICKER_PATH;
    }

    public static IImageLoader getIImageLoader() {
        return mIImageLoader;
    }

    public Pattern getPattern() {
        return mPattern;
    }

    private Pattern makePattern() {
        return Pattern.compile("\\[[^\\[]{1,10}\\]");
    }

    public static int getDisplayCount() {
        return mDefaultEntries.size();
    }

    static String getDisplayText(int index) {
        return index >= 0 && index < mDefaultEntries.size() ? mDefaultEntries.get(index).text : null;
    }

    public static Drawable getDisplayDrawable(Context context, int index) {
        String text = (index >= 0 && index < mDefaultEntries.size() ? mDefaultEntries.get(index).text : null);
        return text == null ? null : getDrawable(context, text);
    }

    /**
     * 获取静态 Drawable，优先从缓存中读取，没有才创建新对象
     *
     * @param context 上下文
     * @param text    表情对应的文本 [微笑] [再见]
     * @return 表情静态 drawable
     */
    private static Drawable getDrawable(Context context, String text) {
        ImageEntry entry = mText2Entry.get(text);
        if (entry == null || TextUtils.isEmpty(entry.text)) {
            return null;
        }
        Bitmap cache = mDrawableCache.get(entry.path);
        if (cache == null) {
            cache = loadAssetBitmap(context, entry.path);
        }
        return new BitmapDrawable(context.getResources(), cache);
    }

    /**
     * 加载asset目录下对应的静态图，无静态图时加载动态图第一帧
     * 在一个 TextView 中显示多个动态表情时为了降低内存消耗会转成静态表情，项目中默认是5个以上就显示静态图
     *
     * @param context   上下文
     * @param assetPath 图片路径（不包含 .格式）
     * @return 静态图的 bitmap 对象
     */
    private static Bitmap loadAssetBitmap(Context context, String assetPath) {
        InputStream is = null;
        try {
            Resources resources = context.getResources();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDensity = DisplayMetrics.DENSITY_HIGH;
            options.inScreenDensity = resources.getDisplayMetrics().densityDpi;
            options.inTargetDensity = resources.getDisplayMetrics().densityDpi;
            // 显示静态图时优先显示png，无对应png时加载gif第一张
            if (isFileExists(assetPath + ".png")) {
                is = context.getAssets().open(assetPath + ".png");
            } else {
                is = context.getAssets().open(assetPath + ".gif");
            }
            Bitmap bitmap = BitmapFactory.decodeStream(is, new Rect(), options);
            if (bitmap != null) {
                mDrawableCache.put(assetPath, bitmap);
            }
            return bitmap;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    /**
     * 获取 GifDrawable 对象，优先从缓存中读取，没有才创建新对象
     *
     * @param context 上下文
     * @param text    表情对应的文本 [微笑] [再见]
     * @return GifDrawable 对象
     */
    private AnimatedGifDrawable getDrawableGif(Context context, String text) {
        ImageEntry entry = mText2Entry.get(text);
        if (entry == null || TextUtils.isEmpty(entry.text)) {
            return null;
        }
        AnimatedGifDrawable cache = mGifDrawableCache.get(entry.path);
        if (cache == null) {
            cache = loadAssetGif(context, entry.path);
            mGifDrawableCache.put(entry.path, cache);
        }
        return cache;
    }

    /**
     * 去 assetPath 目录下加载动态表情
     *
     * @param context   上下文
     * @param assetPath 路径
     * @return GifDrawable 对象
     */
    private AnimatedGifDrawable loadAssetGif(Context context, String assetPath) {
        InputStream is;
        try {
            is = context.getResources().getAssets().open(assetPath + ".gif");
            return new AnimatedGifDrawable(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 加载解析配置文件
     *
     * @param context 上下文
     * @param xmlPath 配置文件路径
     */
    private void load(Context context, String xmlPath) {
        new EntryLoader().load(context, xmlPath);
        //补充最后一页少的表情,空白占位
        int tmp = mDefaultEntries.size() % EmoticonView.EMOJI_PER_PAGE;
        if (tmp != 0) {
            int tmp2 = EmoticonView.EMOJI_PER_PAGE - (mDefaultEntries.size() - (mDefaultEntries.size()
                    / EmoticonView.EMOJI_PER_PAGE) * EmoticonView.EMOJI_PER_PAGE);
            for (int i = 0; i < tmp2; i++) {
                mDefaultEntries.add(new ImageEntry("", ""));
            }
        }
    }

    /**
     * 表情load对象封装
     */
    private class ImageEntry {
        // 表情对应的文本内容
        private String text;
        // 表情所在的路径
        private String path;

        public ImageEntry(String text, String path) {
            this.text = text;
            this.path = path;
        }
    }

    /**
     * 表情对象加载器,解析配置 xml
     */
    private class EntryLoader extends DefaultHandler {

        private String catalog = "";

        // 解析 asset 中的表情配置文件
        void load(Context context, String path) {
            InputStream is = null;
            try {
                is = context.getAssets().open(path);
                Xml.parse(is, Xml.Encoding.UTF_8, this);
            } catch (IOException | SAXException e) {
                e.printStackTrace();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (localName.equals("Catalog")) {
                catalog = attributes.getValue(uri, "Title");
            } else if (localName.equals("Emoticon")) {
                String tag = attributes.getValue(uri, "Tag");
                String fileName = attributes.getValue(uri, "File");
                ImageEntry entry = new ImageEntry(tag, EMOT_DIR + catalog + File.separator + fileName);
                mText2Entry.put(entry.text, entry);
                if (catalog.equals(SOUCRE_DIR)) {
                    mDefaultEntries.add(entry);
                }
            }
        }
    }

    public static class Builder {
        // 表情包在 assets 文件夹下的目录
        private String EMOT_DIR;
        // 表情包图片资源文件夹名称
        private String SOUCRE_DIR;
        //配置文件名称
        private String mConfigName;
        // 加载表情时内存缓存的最大值
        private int CACHE_MAX_SIZE;
        // 上下文
        private Context mContext;
        // 贴图表情加载
        private IImageLoader mIImageLoader;
        // 贴图表情目录路径
        private String STICKER_PATH;

        public Builder setEMOT_DIR(String EMOT_DIR) {
            this.EMOT_DIR = EMOT_DIR;
            return this;
        }

        public Builder setCACHE_MAX_SIZE(int CACHE_MAX_SIZE) {
            this.CACHE_MAX_SIZE = CACHE_MAX_SIZE;
            return this;
        }

        public Builder setContext(Context context) {
            mContext = context;
            return this;
        }

        public Builder setIImageLoader(IImageLoader IImageLoader) {
            mIImageLoader = IImageLoader;
            return this;
        }

        public Builder setConfigName(String configName) {
            mConfigName = configName;
            return this;
        }

        public Builder setSOUCRE_DIR(String SOUCRE_DIR) {
            this.SOUCRE_DIR = SOUCRE_DIR;
            return this;
        }

        public void setSTICKER_PATH(String STICKER_PATH) {
            this.STICKER_PATH = STICKER_PATH;
        }

        public EmoticonManager build() {
            if (mEmoticonManager == null) {
                synchronized (EmoticonManager.class) {
                    if (mEmoticonManager == null) {
                        mEmoticonManager = new EmoticonManager();
                    }
                }
            }
            if (this.CACHE_MAX_SIZE != 0) {
                mEmoticonManager.CACHE_MAX_SIZE = this.CACHE_MAX_SIZE;
            }
            if (this.EMOT_DIR != null) {
                mEmoticonManager.EMOT_DIR = this.EMOT_DIR;
            }
            if (this.mContext != null) {
                mEmoticonManager.mContext = this.mContext;
            }
            if (this.mIImageLoader != null) {
                EmoticonManager.mIImageLoader = this.mIImageLoader;
            }
            if (this.mConfigName != null) {
                mEmoticonManager.mConfigName = this.mConfigName;
            }
            if (this.SOUCRE_DIR != null) {
                mEmoticonManager.SOUCRE_DIR = this.SOUCRE_DIR;
            }

            if (this.STICKER_PATH != null) {
                mEmoticonManager.STICKER_PATH = this.STICKER_PATH;
            }
            return mEmoticonManager.init();
        }
    }

    /**
     * 判断assets文件夹下的资源文件是否存在
     *
     * @return false 不存在    true 存在
     */
    private static boolean isFileExists(String filename) {
        AssetManager assetManager = mContext.getAssets();
        try {
            String[] names = assetManager.list(EMOT_DIR + File.separator + SOUCRE_DIR);
            for (String name : names) {
                if (("emoji/source/" + name).equals(filename.trim())) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return false;
    }
}
