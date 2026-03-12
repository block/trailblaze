package xyz.block.trailblaze.examples.sampleapp.ui.screens.swipe

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import xyz.block.trailblaze.examples.sampleapp.R

private val PAGE_COLORS =
  intArrayOf(
    AndroidColor.parseColor("#EF5350"),
    AndroidColor.parseColor("#42A5F5"),
    AndroidColor.parseColor("#66BB6A"),
    AndroidColor.parseColor("#FFA726"),
    AndroidColor.parseColor("#AB47BC"),
  )

class SwipePageFragment : Fragment() {
  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val view = inflater.inflate(R.layout.fragment_swipe_page, container, false)
    val pageIndex = arguments?.getInt("page_index") ?: 0
    view.findViewById<TextView>(R.id.tv_page_label).text = "Page ${pageIndex + 1}"
    view.setBackgroundColor(PAGE_COLORS[pageIndex % PAGE_COLORS.size])
    return view
  }

  companion object {
    fun newInstance(pageIndex: Int): SwipePageFragment {
      return SwipePageFragment().apply {
        arguments = Bundle().apply { putInt("page_index", pageIndex) }
      }
    }
  }
}

class SwipePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
  override fun getItemCount(): Int = 5

  override fun createFragment(position: Int): Fragment = SwipePageFragment.newInstance(position)
}

@Composable
fun SwipeScreen() {
  var currentPage by remember { mutableIntStateOf(0) }

  Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = "Page ${currentPage + 1} of 5",
      fontSize = 16.sp,
      modifier = Modifier.padding(12.dp),
    )

    AndroidView(
      modifier = Modifier.fillMaxWidth().weight(1f),
      factory = { context ->
        ViewPager2(context).apply {
          id = View.generateViewId()
          adapter = SwipePagerAdapter(context as FragmentActivity)
          registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
              override fun onPageSelected(position: Int) {
                currentPage = position
              }
            }
          )
        }
      },
    )

    // Page indicator dots
    Text(
      text = (1..5).joinToString("  ") { if (it == currentPage + 1) "●" else "○" },
      fontSize = 20.sp,
      modifier = Modifier.padding(12.dp),
    )
  }
}
