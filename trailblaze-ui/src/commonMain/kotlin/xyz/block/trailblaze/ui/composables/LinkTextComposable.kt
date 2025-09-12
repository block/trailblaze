package xyz.block.trailblaze.ui.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

@Composable
fun LinkText(
  label: String,
  href: String,
  textAlign: TextAlign = TextAlign.Start,
  modifier: Modifier = Modifier,
  // style links like the web: blue + underline
  linkStyles: TextLinkStyles = TextLinkStyles(
    style = SpanStyle(
      color = MaterialTheme.colorScheme.primary,
      textDecoration = TextDecoration.Underline
    )
  ),
) {
  val text: AnnotatedString = buildAnnotatedString {
    withLink(
      LinkAnnotation.Url(
        url = href,
        styles = linkStyles,
        // optional: intercept clicks yourself
        // , linkInteractionListener = { (it as LinkAnnotation.Url).url.let { url -> ... } }
      )
    ) {
      append(label)
    }
  }
  Text(
    text = text,
    textAlign = textAlign,
    modifier = modifier,
  )
}
