package com.dirror.music.widget.lyric

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Scroller
import androidx.core.content.ContextCompat
import com.dirror.music.R
import com.dirror.music.widget.lyric.LyricUtil.formatTime
import com.dirror.music.widget.lyric.LyricUtil.getContentFromNetwork
import com.dirror.music.widget.lyric.LyricUtil.resetDurationScale
import java.io.File
import java.util.*
import kotlin.math.abs

/**
 * 歌词控件
 * 来自 https://github.com/zion223/NeteaseCloudMusic-MVVM，十分感谢
 * 修改 Moriafly
 * 转换为 Kotlin
 */
@SuppressLint("StaticFieldLeak")
class LyricView @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    companion object {
        private const val ADJUST_DURATION: Long = 100
        private const val TIMELINE_KEEP_TIME = 3 * DateUtils.SECOND_IN_MILLIS
    }

    private val lyricEntryList: MutableList<LyricEntry> = ArrayList() // 每一句歌词
    private val lyricPaint = TextPaint() // 歌词画笔
    private val timePaint = TextPaint() // 时间画笔
    private var timeFontMetrics: Paint.FontMetrics? = null
    private var playDrawable: Drawable? = null // 播放按钮
    private var dividerHeight = 0f
    private var animationDuration: Long = 0
    private var normalTextColor = 0
    private var normalTextSize = 0f
    private var currentTextColor = 0
    private var currentTextSize = 0f
    private var timelineTextColor = 0
    private var timelineColor = 0
    private var timeTextColor = 0
    private var drawableWidth = 0
    private var timeTextWidth = 0
    private var defaultLabel: String = ""
    private var lrcPadding = 0f
    private var onPlayClickListener: OnPlayClickListener? = null
    private var onSingerClickListener: OnSingleClickListener? = null
    private var animator: ValueAnimator? = null
    private var gestureDetector: GestureDetector? = null
    private var scroller: Scroller? = null
    private var offset = 0f
    private var currentLine = 0
    private var flag: Any? = null
    private var isShowTimeline = false // 显示时间线
    private var isTouching = false
    private var isFling = false
    private var textGravity = 0 // 歌词显示位置，靠左 / 居中 / 靠右

    /**
     * 播放按钮点击监听器，点击后应该跳转到指定播放位置
     */
    interface OnPlayClickListener {
        /**
         * 播放按钮被点击，应该跳转到指定播放位置
         * @return 是否成功消费该事件，如果成功消费，则会更新UI
         */
        fun onPlayClick(time: Long): Boolean
    }

    /**
     * 点击歌词布局
     */
    interface OnSingleClickListener {
        fun onClick()
    }

    init {
        init(attrs)
    }

    /**
     * 初始化
     */
    @SuppressLint("CustomViewStyleable")
    private fun init(attrs: AttributeSet?) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LrcView)
        currentTextSize = ta.getDimension(R.styleable.LrcView_lrcTextSize, resources.getDimension(R.dimen.lrc_text_size))
        normalTextSize =
            ta.getDimension(R.styleable.LrcView_lrcNormalTextSize, resources.getDimension(R.dimen.lrc_text_size))
        if (normalTextSize == 0f) {
            normalTextSize = currentTextSize
        }
        dividerHeight =
            ta.getDimension(R.styleable.LrcView_lrcDividerHeight, resources.getDimension(R.dimen.lrc_divider_height))
        val defDuration = resources.getInteger(R.integer.lrc_animation_duration)
        animationDuration = ta.getInt(R.styleable.LrcView_lrcAnimationDuration, defDuration).toLong()
        animationDuration = if (animationDuration < 0) defDuration.toLong() else animationDuration
        normalTextColor = ta.getColor(
            R.styleable.LrcView_lrcNormalTextColor,
            ContextCompat.getColor(context, R.color.lrc_normal_text_color)
        )
        currentTextColor = ta.getColor(
            R.styleable.LrcView_lrcCurrentTextColor,
            ContextCompat.getColor(context, R.color.lrc_current_text_color)
        )
        timelineTextColor = ta.getColor(
            R.styleable.LrcView_lrcTimelineTextColor,
            ContextCompat.getColor(context, R.color.lrc_timeline_text_color)
        )
        defaultLabel = ta.getString(R.styleable.LrcView_lrcLabel).toString()
        defaultLabel = if (TextUtils.isEmpty(defaultLabel)) "暂无歌词" else defaultLabel
        // mDefaultLabel = TextUtils.isEmpty(mDefaultLabel) ? getContext().getString(R.string.lrc_label) : mDefaultLabel;
        lrcPadding = ta.getDimension(R.styleable.LrcView_lrcPadding, 0f)
        timelineColor =
            ta.getColor(R.styleable.LrcView_lrcTimelineColor, ContextCompat.getColor(context, R.color.lrc_timeline_color))
        val timelineHeight =
            ta.getDimension(R.styleable.LrcView_lrcTimelineHeight, resources.getDimension(R.dimen.lrc_timeline_height))
        playDrawable = ta.getDrawable(R.styleable.LrcView_lrcPlayDrawable)
        playDrawable = if (playDrawable == null) ContextCompat.getDrawable(context, R.drawable.lrc_play) else playDrawable
        timeTextColor =
            ta.getColor(R.styleable.LrcView_lrcTimeTextColor, ContextCompat.getColor(context, R.color.lrc_time_text_color))
        val timeTextSize =
            ta.getDimension(R.styleable.LrcView_lrcTimeTextSize, resources.getDimension(R.dimen.lrc_time_text_size))
        textGravity = ta.getInteger(R.styleable.LrcView_lrcTextGravity, LyricEntry.GRAVITY_CENTER)
        ta.recycle()
        drawableWidth = resources.getDimension(R.dimen.lrc_drawable_width).toInt()
        timeTextWidth = resources.getDimension(R.dimen.lrc_time_width).toInt()
        lyricPaint.isAntiAlias = true
        lyricPaint.textSize = currentTextSize
        lyricPaint.textAlign = Paint.Align.LEFT
        timePaint.isAntiAlias = true
        timePaint.textSize = timeTextSize
        timePaint.textAlign = Paint.Align.CENTER
        timePaint.strokeWidth = timelineHeight
        timePaint.strokeCap = Paint.Cap.ROUND
        timeFontMetrics = timePaint.fontMetrics
        gestureDetector = GestureDetector(context, mSimpleOnGestureListener)
        gestureDetector!!.setIsLongpressEnabled(false)
        scroller = Scroller(context)
    }

    /**
     * 设置非当前行歌词字体颜色
     */
    fun setNormalColor(normalColor: Int) {
        normalTextColor = normalColor
        postInvalidate()
    }

    /**
     * 普通歌词文本字体大小
     */
    fun setNormalTextSize(size: Float) {
        normalTextSize = size
    }

    /**
     * 当前歌词文本字体大小
     */
    fun setCurrentTextSize(size: Float) {
        currentTextSize = size
    }

    /**
     * 设置当前行歌词的字体颜色
     */
    fun setCurrentColor(currentColor: Int) {
        currentTextColor = currentColor
        postInvalidate()
    }

    /**
     * 设置拖动歌词时选中歌词的字体颜色
     */
    fun setTimelineTextColor(timelineTextColor: Int) {
        this.timelineTextColor = timelineTextColor
        postInvalidate()
    }

    /**
     * 设置拖动歌词时时间线的颜色
     */
    fun setTimelineColor(timelineColor: Int) {
        this.timelineColor = timelineColor
        postInvalidate()
    }

    /**
     * 设置拖动歌词时右侧时间字体颜色
     */
    fun setTimeTextColor(timeTextColor: Int) {
        this.timeTextColor = timeTextColor
        postInvalidate()
    }

    /**
     * 设置歌词是否允许拖动
     * @param draggable           是否允许拖动
     * @param onPlayClickListener 设置歌词拖动后播放按钮点击监听器，如果允许拖动，则不能为 null
     */
    fun setDraggable(draggable: Boolean, onPlayClickListener: OnPlayClickListener?) {
        this.onPlayClickListener = if (draggable) {
            requireNotNull(onPlayClickListener) { "if draggable == true, onPlayClickListener must not be null" }
            onPlayClickListener
        } else {
            null
        }
    }

    /**
     * 设置点击布局
     */
    fun setOnSingerClickListener(mOnSingerClickListener: OnSingleClickListener?) {
        this.onSingerClickListener = mOnSingerClickListener
    }

    /**
     * 设置歌词为空时屏幕中央显示的文字，如“暂无歌词”
     */
    fun setLabel(label: String) {
        runOnUi {
            defaultLabel = label
            this@LyricView.invalidate()
        }
    }

    /**
     * 加载双语歌词文件，两种语言的歌词时间戳需要一致
     * @param mainLrcFile   第一种语言歌词文件
     * @param secondLrcFile 第二种语言歌词文件
     */
    @SuppressLint
    @JvmOverloads
    fun loadLrc(mainLrcFile: File, secondLrcFile: File? = null) {
        runOnUi {
            reset()
            val sb = StringBuilder("file://")
            sb.append(mainLrcFile.path)
            if (secondLrcFile != null) {
                sb.append("#").append(secondLrcFile.path)
            }
            val flag = sb.toString()
            this@LyricView.flag = flag
            object : AsyncTask<File?, Int?, List<LyricEntry>>() {
                override fun onPostExecute(lrcEntries: List<LyricEntry>?) {
                    if (flag === flag) {
                        onLrcLoaded(lrcEntries)
                        this@LyricView.flag = null
                    }
                }

                override fun doInBackground(vararg params: File?): List<LyricEntry>? {
                    return LyricUtil.parseLrc(params)
                }
            }.execute(mainLrcFile, secondLrcFile)
        }
    }

    /**
     * 加载双语歌词文本，两种语言的歌词时间戳需要一致
     * @param mainLrcText   第一种语言歌词文本
     * @param secondLrcText 第二种语言歌词文本
     */
    @JvmOverloads
    fun loadLrc(mainLrcText: String?, secondLrcText: String? = null) {
        runOnUi {
            reset()
            val sb = StringBuilder("file://")
            sb.append(mainLrcText)
            if (secondLrcText != null) {
                sb.append("#").append(secondLrcText)
            }
            val flag = sb.toString()
            this@LyricView.flag = flag
            object : AsyncTask<String, Int, List<LyricEntry>>() {
                override fun doInBackground(vararg params: String?): List<LyricEntry>? {
                    return LyricUtil.parseLrc(params)
                }

                override fun onPostExecute(lrcEntries: List<LyricEntry>?) {
                    if (flag === flag) {
                        onLrcLoaded(lrcEntries)
                        this@LyricView.flag = null
                    }
                }
            }.execute(mainLrcText, secondLrcText)
        }
    }

    /**
     * 加载在线歌词，默认使用 utf-8 编码
     *
     * @param lrcUrl 歌词文件的网络地址
     * @param charset 编码格式，默认使用 utf-8 编码
     */
    @JvmOverloads
    fun loadLrcByUrl(lrcUrl: String, charset: String? = "utf-8") {
        val flag = "url://$lrcUrl"
        this.flag = flag
        object : AsyncTask<String?, Int?, String?>() {
            override fun onPostExecute(lrcText: String?) {
                if (flag === flag) {
                    loadLrc(lrcText)
                }
            }

            override fun doInBackground(vararg params: String?): String? {
                return getContentFromNetwork(params[0], params[1])
            }
        }.execute(lrcUrl, charset)
    }

    /**
     * 歌词是否有效
     * @return true，如果歌词有效，否则false
     */
    fun hasLrc(): Boolean {
        return lyricEntryList.isNotEmpty()
    }

    /**
     * 刷新歌词
     * @param time 当前播放时间
     */
    fun updateTime(time: Long) {
        if (hasLrc()) {
            runOnUi {
                val line = findShowLine(time)
                if (line != currentLine) {
                    currentLine = line
                    if (isShowTimeline) {
                        this@LyricView.invalidate()
                    } else {
                        smoothScrollTo(line)
                    }
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            initPlayDrawable()
            initEntryList()
            if (hasLrc()) {
                smoothScrollTo(currentLine, 0L)
            }
        }
    }

    /**
     * 绘制
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2
        // 无歌词文件
        if (!hasLrc()) {
            lyricPaint.color = currentTextColor
            val staticLayoutBuilder = StaticLayout.Builder
                .obtain(defaultLabel, 0, defaultLabel.length, lyricPaint, lrcWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
            val staticLayout = staticLayoutBuilder.build()
            drawText(canvas, staticLayout, centerY.toFloat())
            return
        }
        val centerLine = centerLine
        if (isShowTimeline) {
            playDrawable!!.draw(canvas)
            timePaint.color = timelineColor
            canvas.drawLine(
                timeTextWidth.toFloat(),
                centerY.toFloat(),
                (width - timeTextWidth).toFloat(),
                centerY.toFloat(),
                timePaint
            )
            timePaint.color = timeTextColor
            val timeText = formatTime(lyricEntryList[centerLine].time)
            val timeX = width - timeTextWidth.toFloat() / 2
            val timeY = centerY - (timeFontMetrics!!.descent + timeFontMetrics!!.ascent) / 2
            canvas.drawText(timeText, timeX, timeY, timePaint)
        }
        canvas.translate(0f, offset)
        var y = 0f
        for (i in lyricEntryList.indices) {
            if (i > 0) {
                y += (lyricEntryList[i - 1].height + lyricEntryList[i].height shr 1) + dividerHeight
            }
            if (i == currentLine) {
                lyricPaint.textSize = currentTextSize
                lyricPaint.color = currentTextColor
                // 中间行歌词显示在 BottomMusicView 上 LrcView 生命周期与 BottomMusicView 不同  TODO
                // Log.e("LrcView", mLrcEntryList.get(mCurrentLine).getText());
            } else if (isShowTimeline && i == centerLine) {
                lyricPaint.color = timelineTextColor
            } else {
                lyricPaint.textSize = normalTextSize
                lyricPaint.color = normalTextColor
            }
            lyricEntryList[i].staticLayout?.let { drawText(canvas, it, y) }
        }
    }

    /**
     * 画一行歌词
     * @param y 歌词中心 Y 坐标
     */
    private fun drawText(canvas: Canvas, staticLayout: StaticLayout, y: Float) {
        canvas.save()
        canvas.translate(lrcPadding, y - (staticLayout.height shr 1))
        staticLayout.draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isTouching = false
            if (hasLrc() && !isFling) {
                adjustCenter()
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME)
            }
        }
        return gestureDetector!!.onTouchEvent(event)
    }

    /**
     * 手势监听器
     */
    private val mSimpleOnGestureListener: SimpleOnGestureListener = object : SimpleOnGestureListener() {
        /**
         * 按下
         */
        override fun onDown(e: MotionEvent): Boolean {
            // 有歌词并且设置了 mOnPlayClickListener
            if (hasLrc() && onPlayClickListener != null) {
                scroller!!.forceFinished(true)
                removeCallbacks(hideTimelineRunnable)
                isTouching = true
                // isShowTimeline = true;
                invalidate()
                return true
            }
            return super.onDown(e)
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (hasLrc()) {
                // 滚动显示时间线
                isShowTimeline = true
                offset += -distanceY
                offset = offset.coerceAtMost(getOffset(0))
                offset = offset.coerceAtLeast(getOffset(lyricEntryList.size - 1))
                invalidate()
                return true
            }
            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (hasLrc()) {
                scroller!!.fling(
                    0,
                    offset.toInt(),
                    0,
                    velocityY.toInt(),
                    0,
                    0,
                    getOffset(lyricEntryList.size - 1).toInt(),
                    getOffset(0).toInt()
                )
                isFling = true
                return true
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (hasLrc() && isShowTimeline && playDrawable!!.bounds.contains(e.x.toInt(), e.y.toInt())) {
                val centerLine = centerLine
                val centerLineTime = lyricEntryList[centerLine].time
                // onPlayClick 消费了才更新 UI
                if (onPlayClickListener != null && onPlayClickListener!!.onPlayClick(centerLineTime)) {
                    isShowTimeline = false
                    removeCallbacks(hideTimelineRunnable)
                    currentLine = centerLine
                    invalidate()
                    return true
                }
            } else {
                if (onSingerClickListener != null) {
                    onSingerClickListener!!.onClick()
                }
            }
            return super.onSingleTapConfirmed(e)
        }
    }

    private val hideTimelineRunnable = Runnable {
        if (hasLrc() && isShowTimeline) {
            isShowTimeline = false
            smoothScrollTo(currentLine)
        }
    }

    override fun computeScroll() {
        if (scroller!!.computeScrollOffset()) {
            offset = scroller!!.currY.toFloat()
            invalidate()
        }
        if (isFling && scroller!!.isFinished) {
            isFling = false
            if (hasLrc() && !isTouching) {
                adjustCenter()
                postDelayed(hideTimelineRunnable, TIMELINE_KEEP_TIME)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideTimelineRunnable)
        super.onDetachedFromWindow()
    }

    private fun onLrcLoaded(entryList: List<LyricEntry>?) {
        if (entryList != null && entryList.isNotEmpty()) {
            lyricEntryList.addAll(entryList)
        }
        lyricEntryList.sort()
        initEntryList()
        invalidate()
    }

    private fun initPlayDrawable() {
        val l = (timeTextWidth - drawableWidth) / 2
        val t = height / 2 - drawableWidth / 2
        val r = l + drawableWidth
        val b = t + drawableWidth
        playDrawable!!.setBounds(l, t, r, b)
    }

    private fun initEntryList() {
        if (!hasLrc() || width == 0) {
            return
        }
        for (lrcEntry in lyricEntryList) {
            lrcEntry.init(lyricPaint, lrcWidth.toInt(), textGravity)
        }
        offset = height.toFloat() / 2
    }

    private fun reset() {
        endAnimation()
        scroller!!.forceFinished(true)
        isShowTimeline = false
        isTouching = false
        isFling = false
        removeCallbacks(hideTimelineRunnable)
        lyricEntryList.clear()
        offset = 0f
        currentLine = 0
        invalidate()
    }

    /**
     * 将中心行微调至正中心
     */
    private fun adjustCenter() {
        smoothScrollTo(centerLine, ADJUST_DURATION)
    }

    /**
     * 滚动到某一行
     */
    private fun smoothScrollTo(line: Int, duration: Long = animationDuration) {
        val offset = getOffset(line)
        endAnimation()
        animator = ValueAnimator.ofFloat(this.offset, offset).apply {
            setDuration(duration)
            interpolator = LinearInterpolator()
            addUpdateListener { animation: ValueAnimator ->
                this@LyricView.offset = animation.animatedValue as Float
                this@LyricView.invalidate()
            }
            resetDurationScale()
            start()
        }
    }

    /**
     * 结束滚动动画
     */
    private fun endAnimation() {
        animator?.let {
            if (it.isRunning) {
                it.end()
            }
        }
    }

    /**
     * 二分法查找当前时间应该显示的行数（最后一个 <= time 的行数）
     */
    private fun findShowLine(time: Long): Int {
        var left = 0
        var right = lyricEntryList.size
        while (left <= right) {
            val middle = (left + right) / 2
            val middleTime = lyricEntryList[middle].time
            if (time < middleTime) {
                right = middle - 1
            } else {
                if (middle + 1 >= lyricEntryList.size || time < lyricEntryList[middle + 1].time) {
                    return middle
                }
                left = middle + 1
            }
        }
        return 0
    }

    /**
     * 获取当前在视图中央的行数
     */
    private val centerLine: Int
        get() {
            var centerLine = 0
            var minDistance = Float.MAX_VALUE
            for (i in lyricEntryList.indices) {
                if (abs(offset - getOffset(i)) < minDistance) {
                    minDistance = abs(offset - getOffset(i))
                    centerLine = i
                }
            }
            return centerLine
        }

    /**
     * 获取歌词距离视图顶部的距离
     * 采用懒加载方式
     */
    private fun getOffset(line: Int): Float {
        if (lyricEntryList[line].offset == Float.MIN_VALUE) {
            var offset = height.toFloat() / 2
            for (i in 1..line) {
                offset -= (lyricEntryList[i - 1].height + lyricEntryList[i].height shr 1) + dividerHeight
            }
            lyricEntryList[line].offset = offset
        }
        return lyricEntryList[line].offset
    }

    /**
     * 获取歌词宽度
     */
    private val lrcWidth: Float
        get() = width - lrcPadding * 2

    /**
     * 在主线程中运行
     */
    private fun runOnUi(runnable: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            post(runnable)
        }
    }

}