package com.jqorz.picbox;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.jelly.mango.Mango;
import com.jelly.mango.MultiplexImage;
import com.jqorz.picbox.adapter.ImageAdapter;
import com.jqorz.picbox.model.ImageModel;
import com.jqorz.picbox.utils.Config;
import com.jqorz.picbox.utils.FileUtil;
import com.jqorz.picbox.utils.ImageSearch;
import com.jqorz.picbox.utils.LockUtil;
import com.jqorz.picbox.utils.Logg;
import com.jqorz.picbox.utils.ToastUtil;
import com.jqorz.picbox.view.TitleItemDecoration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.schedulers.Schedulers;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.os.Build.VERSION_CODES.M;

public class MainActivity extends AppCompatActivity implements SwipeRefreshLayout.OnRefreshListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1;
    private Disposable searchDisposable;
    private Disposable lockDisposable;
    private ImageAdapter mImageAdapter;
    private int GRID_COLUMN_SIZE = 3;
    private RecyclerView mRecyclerView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private View emptyTipView;
    private LockUtil lockUtil;
    private ProgressBar mProgressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initRecyclerView();
        start();
        lockUtil = new LockUtil();
    }

    @TargetApi(M)
    private void requestPermissions() {
        final String[] permissions = new String[]{WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE};

        boolean showRationale = false;
        for (String perm : permissions) {
            showRationale |= shouldShowRequestPermissionRationale(perm);
        }
        if (!showRationale) {
            requestPermissions(permissions, REQUEST_PERMISSIONS);
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.my_app_name) + "需要存储权限才能正常使用")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestPermissions(permissions, REQUEST_PERMISSIONS);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
    }

    private void initRecyclerView() {
        mImageAdapter = new ImageAdapter(R.layout.item_image, GRID_COLUMN_SIZE);
        mImageAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Mango.setTitle("图片浏览");
                List<ImageModel> models = mImageAdapter.getData();
                Mango.setImages(loadImage(models)); //设置图片源
                Mango.setPosition(position); //设置初始显示位置
                Mango.open(MainActivity.this); //开启图片浏览
            }

        });
        mImageAdapter.bindToRecyclerView(mRecyclerView);

        //设置分割线
        TitleItemDecoration titleItemDecoration = new TitleItemDecoration(this) {
            @Override
            public boolean calculateShouldHaveHeader(int position) {
                if (position > mImageAdapter.getData().size() - 1)
                    return false;
                else
                    return mImageAdapter.getData().get(position).getNum() >= 0 && mImageAdapter.getData().get(position).getNum() < GRID_COLUMN_SIZE;
            }

            @Override
            public String getTag(int position) {
                if (position > mImageAdapter.getData().size() - 1) {
                    return "";
                } else {
                    return mImageAdapter.getData().get(position).getDate();
                }
            }

            @Override
            public boolean calculateShouldHaveHeaderPadding(int position) {
                if (position > mImageAdapter.getData().size() - 1)
                    return false;
                else {//第一组的第一行才留白
                    return mImageAdapter.getData().get(position).getGroup() == 0 && calculateShouldHaveHeader(position);
                }
            }
        };
        mRecyclerView.addItemDecoration(titleItemDecoration);

        //解决刷新闪烁的问题
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        mRecyclerView.setItemAnimator(itemAnimator);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, GRID_COLUMN_SIZE);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                //通过getSpanSize进行占位
                if (position < mImageAdapter.getData().size() - 1) {
                    ImageModel resourceBean = mImageAdapter.getData().get(position);
                    //如果是最后一个
                    if (resourceBean.getNum() == resourceBean.getGroupNum() - 1) {
                        return GRID_COLUMN_SIZE - resourceBean.getNum() % GRID_COLUMN_SIZE;
                    }
                }
                return 1;
            }
        });
        mRecyclerView.setLayoutManager(gridLayoutManager);
    }

    private boolean hasPermissions() {
        PackageManager pm = getPackageManager();
        String packageName = getPackageName();
        int granted = pm.checkPermission(READ_EXTERNAL_STORAGE, packageName) | pm.checkPermission(WRITE_EXTERNAL_STORAGE, packageName);
        return granted == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (!hasPermissions()) {
            requestPermissions();
        }
        if (searchDisposable != null && !searchDisposable.isDisposed()) return;
        mSwipeRefreshLayout.setRefreshing(true);

        searchDisposable = Observable.just(Config.SCREEN_CAPTURE)
                .flatMap(new Function<String, ObservableSource<File>>() {
                    @Override
                    public ObservableSource<File> apply(String s) throws Exception {
                        return ImageSearch.listImageFiles(new File(s));
                    }
                })
                .map(new Function<File, ImageModel>() {
                    @Override
                    public ImageModel apply(File file) throws Exception {
                        long time = file.lastModified();
                        return new ImageModel(time, file.getAbsolutePath(), ImageSearch.isLock(file));
                    }
                })
                .sorted(new Comparator<ImageModel>() {
                    @Override
                    public int compare(ImageModel o1, ImageModel o2) {
                        //按照时间倒序排列
                        return Long.compare(o2.getLongTime(), o1.getLongTime());
                    }
                })
                .toList()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        mSwipeRefreshLayout.setRefreshing(false);
                        emptyTipView.setVisibility((mImageAdapter.getData().size() > 0) ? View.GONE : View.VISIBLE);
                    }
                })
                .map(new Function<List<ImageModel>, List<ImageModel>>() {
                    @Override
                    public List<ImageModel> apply(List<ImageModel> mData) throws Exception {
                        convertData(mData);//排序
                        return mData;
                    }
                })
                .subscribe(new Consumer<List<ImageModel>>() {
                    @Override
                    public void accept(List<ImageModel> imageModels) throws Exception {
                        mImageAdapter.setNewData(imageModels);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        Log.e(TAG, "error = " + throwable.getMessage());
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            int granted = PackageManager.PERMISSION_GRANTED;
            for (int r : grantResults) {
                granted |= r;
            }
            if (granted == PackageManager.PERMISSION_GRANTED) {
                start();
            } else {
                Toast.makeText(this, "请先授予存储区权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    //图片列表
    public List<MultiplexImage> loadImage(List<ImageModel> models) {
        List<MultiplexImage> images = new ArrayList<>();
        for (int i = 0, j = models.size(); i < j; i++) {
            String path = models.get(i).getPath();
            images.add(new MultiplexImage(path, MultiplexImage.ImageType.NORMAL));
        }
        return images;
    }

    /**
     * 将数据按照日期进行分组
     */
    private void convertData(List<ImageModel> mData) {
        LinkedHashMap<String, List<ImageModel>> skuIdMap = new LinkedHashMap<>();
        for (ImageModel resourceBean : mData) {
            List<ImageModel> tempList = skuIdMap.get(resourceBean.getDate());
            //如果取不到数据,那么直接new一个空的ArrayList
            if (tempList == null) {
                tempList = new ArrayList<>();
                tempList.add(resourceBean);
                skuIdMap.put(resourceBean.getDate(), tempList);
            } else {
                //某个sku之前已经存放过了,则直接追加数据到原来的List里
                tempList.add(resourceBean);
            }
        }

        Iterator<String> it = skuIdMap.keySet().iterator();
        for (int group = 0; it.hasNext(); group++) {
            List<ImageModel> imageModels = skuIdMap.get(it.next());
            for (int i = 0, j = imageModels.size(); i < j; i++) {
                imageModels.get(i).setNum(i);
                imageModels.get(i).setGroupNum(j);
                imageModels.get(i).setGroup(group);
            }
        }
        mData.clear();
        for (List<ImageModel> list : skuIdMap.values()) {
            mData.addAll(list);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_function, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_lock:
                int sum = mImageAdapter.getData().size();
                if (sum > 0) {
                    int lockSize = 0;
                    for (ImageModel imageModel : mImageAdapter.getData()) {
                        if (imageModel.isLock()) {
                            lockSize++;
                        }
                    }
//                    if (sum == lockSize) {
//                        ToastUtil.showToast("共有" + sum + "张图片，已经全部加密");
//                        return false;
//                    }
                    showLockDialog(sum, lockSize);
                } else {
                    item.setEnabled(false);
                }

                break;
        }
        return false;
    }

    private void showLockDialog(int sum, int lockSize, boolean isLock) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("一键加密")
                .setMessage("共有" + sum + "张图片，其中" + lockSize + "已加密，" + (sum - lockSize) + "张未加密")
                .setNegativeButton("取消", null)
                .setPositiveButton(isLock ? "加密" : "解密", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mProgressBar.setVisibility(View.VISIBLE);
                        lockDisposable = Observable.fromIterable(mImageAdapter.getData())
                                .filter(new Predicate<ImageModel>() {
                                    @Override
                                    public boolean test(ImageModel imageModel) throws Exception {
                                        return !imageModel.isLock();
                                    }
                                })
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .doOnNext(new Consumer<ImageModel>() {
                                    @Override
                                    public void accept(ImageModel imageModel) throws Exception {
                                        lockUtil.lock(new File(imageModel.getPath()));
                                    }
                                })
                                .doFinally(new Action() {
                                    @Override
                                    public void run() throws Exception {
                                        mProgressBar.setVisibility(View.GONE);
                                    }
                                })
                                .doOnError(new Consumer<Throwable>() {
                                    @Override
                                    public void accept(Throwable throwable) throws Exception {
                                        ToastUtil.showToast("加密失败");
                                    }
                                })
                                .subscribe(new Consumer<ImageModel>() {
                                    @Override
                                    public void accept(ImageModel imageModel) throws Exception {
                                        ToastUtil.showToast(FileUtil.getFileNameNoEx(imageModel.getPath()) + " 加密成功");
                                    }
                                }, new Consumer<Throwable>() {
                                    @Override
                                    public void accept(Throwable throwable) throws Exception {
                                        Logg.e(throwable.getMessage());
                                    }
                                });
                    }
                }).show();
    }

    private void initView() {

        mRecyclerView = findViewById(R.id.rl_image);

        emptyTipView = findViewById(R.id.empty_tip);

        mProgressBar = findViewById(R.id.mProgressBar);
        mProgressBar.setVisibility(View.GONE);

        mSwipeRefreshLayout = findViewById(R.id.mSwipeRefreshLayout);
        mSwipeRefreshLayout.setOnRefreshListener(this);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchDisposable != null && !searchDisposable.isDisposed()) {
            searchDisposable.dispose();
        }
        if (lockDisposable != null && !lockDisposable.isDisposed()) {
            lockDisposable.dispose();
        }
    }

    @Override
    public void onRefresh() {
        start();
    }
}
