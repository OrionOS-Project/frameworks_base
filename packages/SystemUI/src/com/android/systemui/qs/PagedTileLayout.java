package com.android.systemui.qs;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.ViewPager;
import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;

public class PagedTileLayout extends ViewPager implements QSTileLayout {

    private static final boolean DEBUG = false;

    private static final String TAG = "PagedTileLayout";

    private final ArrayList<TileRecord> mTiles = new ArrayList<TileRecord>();
    private final ArrayList<TilePage> mPages = new ArrayList<TilePage>();

    private PageIndicator mPageIndicator;

    private int mNumPages;
    private View mDecorGroup;
    private PageListener mPageListener;

    public PagedTileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setAdapter(mAdapter);
        setOnPageChangeListener(new OnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (mPageIndicator == null) return;
                if (mPageListener != null) {
                    mPageListener.onPageChanged(position == 0);
                }
            }

            @Override
            public void onPageScrolled(int position, float positionOffset,
                    int positionOffsetPixels) {
                if (mPageIndicator == null) return;
                mPageIndicator.setLocation(position + positionOffset);
                if (mPageListener != null) {
                    mPageListener.onPageChanged(position == 0 && positionOffsetPixels == 0);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
        setCurrentItem(0);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPageIndicator = (PageIndicator) findViewById(R.id.page_indicator);
        mDecorGroup = findViewById(R.id.page_decor);
        ((LayoutParams) mDecorGroup.getLayoutParams()).isDecor = true;

        mPages.add((TilePage) LayoutInflater.from(mContext)
                .inflate(R.layout.qs_paged_page, this, false));
    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        final ViewGroup parent = (ViewGroup) tile.tileView.getParent();
        if (parent == null) return 0;
        return parent.getTop() + getTop();
    }

    @Override
    public void addTile(TileRecord tile) {
        mTiles.add(tile);
        postDistributeTiles();
    }

    @Override
    public void removeTile(TileRecord tile) {
        if (mTiles.remove(tile)) {
            postDistributeTiles();
        }
    }

    public void setPageListener(PageListener listener) {
        mPageListener = listener;
    }

    private void postDistributeTiles() {
        removeCallbacks(mDistribute);
        post(mDistribute);
    }

    private void distributeTiles() {
        if (DEBUG) Log.d(TAG, "Distributing tiles");
        final int NP = mPages.size();
        for (int i = 0; i < NP; i++) {
            mPages.get(i).removeAllViews();
        }
        int index = 0;
        final int NT = mTiles.size();
        for (int i = 0; i < NT; i++) {
            TileRecord tile = mTiles.get(i);
            if (mPages.get(index).isFull()) {
                if (++index == mPages.size()) {
                    if (DEBUG) Log.d(TAG, "Adding page for " + tile.tile.getClass().getSimpleName());
                    mPages.add((TilePage) LayoutInflater.from(mContext)
                            .inflate(R.layout.qs_paged_page, this, false));
                }
            }
            if (DEBUG) Log.d(TAG, "Adding " + tile.tile.getClass().getSimpleName() + " to "
                    + index);
            mPages.get(index).addTile(tile);
        }
        if (mNumPages != index + 1) {
            mNumPages = index + 1;
            mPageIndicator.setNumPages(mNumPages);
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean updateResources() {
        boolean changed = false;
        for (int i = 0; i < mPages.size(); i++) {
            changed |= mPages.get(i).updateResources();
        }
        if (changed) {
            distributeTiles();
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // The ViewPager likes to eat all of the space, instead force it to wrap to the max height
        // of the pages.
        int maxHeight = 0;
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            int height = getChildAt(i).getMeasuredHeight();
            if (height > maxHeight) {
                maxHeight = height;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), maxHeight + mDecorGroup.getMeasuredHeight());
    }

    private final Runnable mDistribute = new Runnable() {
        @Override
        public void run() {
            distributeTiles();
        }
    };

    public int getColumnCount() {
        if (mPages.size() == 0) return 0;
        return mPages.get(0).mColumns;
    }

    public static class TilePage extends TileLayout {
        private int mMaxRows = 3;

        public TilePage(Context context, AttributeSet attrs) {
            super(context, attrs);
            updateResources();
        }

        @Override
        public boolean updateResources() {
            if (super.updateResources()) {
                mMaxRows = mColumns != 3 ? 2 : 3;
                return true;
            }
            return false;
        }

        public void setMaxRows(int maxRows) {
            mMaxRows = maxRows;
        }

        public boolean isFull() {
            return mRecords.size() >= mColumns * mMaxRows;
        }
    }

    private final PagerAdapter mAdapter = new PagerAdapter() {
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (DEBUG) Log.d(TAG, "Destantiating " + position);
            // TODO: Find way to clean up the extra pages.
            container.removeView((View) object);
        }

        public Object instantiateItem(ViewGroup container, int position) {
            if (DEBUG) Log.d(TAG, "Instantiating " + position);
            ViewGroup view = mPages.get(position);
            container.addView(view);
            return view;
        }

        @Override
        public int getCount() {
            return mNumPages;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    };

    public interface PageListener {
        void onPageChanged(boolean isFirst);
    }
}
