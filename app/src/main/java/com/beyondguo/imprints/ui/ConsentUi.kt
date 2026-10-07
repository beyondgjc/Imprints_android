package com.beyondguo.imprints.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beyondguo.imprints.privacy.ConsentState
import androidx.compose.ui.platform.LocalContext

/** 协议正文页：可从首次弹窗进入，也可从设置「关于」进入。 */
@Composable
internal fun PolicyDocumentPage(
    title: String,
    assetName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val body = remember(assetName) { ConsentState.readAsset(context, assetName) }
    // 协议页独立于主 Scaffold：自己处理系统栏内边距，避免顶部被状态栏遮住
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink)
            }
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.height(1.dp).fillMaxWidth().background(Line))
        Text(
            body,
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            color = Ink,
            fontSize = 14.sp,
            lineHeight = 24.sp,
        )
    }
}

/**
 * 首次冷启隐私同意弹窗。
 *
 * 商店审核的三个硬性要求：
 * 1. 首次启动即弹出，且**同意前不初始化 Room / Keystore**（由 MainActivity 门控）
 * 2. 「同意」与「不同意」都真实可点，不禁用、不预选
 * 3. 提供查看全文的入口（正文内链接可进 [PolicyDocumentPage]）
 *
 * 尺寸取向：比对市面主流合规弹窗后收敛——宽度 0.76、正文两段、按钮**并排**。
 * 早期版本用 0.88 宽 + 4 条编号条款 + 全宽堆叠按钮，在小屏上几乎占满整屏，
 * 反而让「不同意」看起来像是被挤在角落。堆叠还多占一整行高度，并排更省。
 */
@Composable
internal fun ConsentDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    var showPolicy by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }

    if (showPolicy) {
        PolicyDocumentPage("隐私政策", "privacy_policy.txt", onBack = { showPolicy = false })
        return
    }
    if (showAgreement) {
        PolicyDocumentPage("用户协议", "user_agreement.txt", onBack = { showAgreement = false })
        return
    }

    Dialog(
        // 不可点外部、不可返回键绕过 —— 必须明确做出选择
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth(0.76f),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text(
                    "个人信息保护指引",
                    Modifier.fillMaxWidth(),
                    color = Ink, fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                // 单段不分段：「不收集」与「位置是唯一例外」是同一条说明的正反面，
                // 拆成两段会读成两条并列事实，还多出一个段距。链接内嵌随文流排版。
                Text(
                    text = buildAnnotatedString {
                        append("印迹只把记录存在你自己的手机上，不注册、不登录、不上传，我们不会收集你的个人信息。" +
                            "唯一需要联网的是你主动添加地点时 —— 印迹会请求位置权限并向高德服务查询附近地名，你可以拒绝，不影响其他功能。详情见")
                        policyLink("《隐私政策》", onClick = { showPolicy = true })
                        append("与")
                        policyLink("《用户协议》", onClick = { showAgreement = true })
                        append("。")
                    },
                    color = Ink, fontSize = 14.sp, lineHeight = 22.sp,
                )
                Spacer(Modifier.height(20.dp))
                // 并排：同意在右（主操作）、不同意在左。两枚都可点、尺寸相近，
                // 视觉层级只靠底色区分，不用「禁用/缩小」做诱导。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = onDisagree,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = BorderStroke(1.dp, Line),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("不同意", fontSize = 15.sp, maxLines = 1)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        Button(
                            onClick = onAgree,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text("同意并继续", fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 在 AnnotatedString 里追加一个可点链接。
 * 品牌绿、不加下划线（参考合规弹窗惯例，下划线会显得像网页链接）。
 *
 * 注意：LinkAnnotation.Clickable 的实际签名是
 * `Clickable(tag, styles, linkInteractionListener)`（无 label/onClick 便捷构造），
 * 见 ui-text-android 1.11.0 LinkAnnotation.kt。
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.policyLink(
    label: String,
    onClick: () -> Unit,
) {
    withLink(
        LinkAnnotation.Clickable(
            tag = label,
            styles = TextLinkStyles(SpanStyle(color = Green, fontWeight = FontWeight.Medium)),
            linkInteractionListener = { onClick() },
        )
    ) {
        append(label)
    }
}
