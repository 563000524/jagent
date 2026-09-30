<script setup>
import { computed } from 'vue'
import { iconSvg } from '../utils/fileIcons'

const props = defineProps({
  /** 文件名（按扩展名选图标） */
  name: { type: String, default: '' },
  /** 边长（px） */
  size: { type: Number, default: 26 }
})

// 静态 SVG 字符串，内容只来自本地常量，不涉及用户输入 → v-html 无注入面
const svg = computed(() => iconSvg(props.name))
</script>

<template>
  <span class="fi" :style="{ width: size + 'px', height: size + 'px' }" v-html="svg"></span>
</template>

<style scoped>
.fi {
  flex: none;
  display: block;
  line-height: 0;
}

/* v-html 的内容不在 scoped 作用域里，必须用 :deep() 才吃得到样式 */
.fi :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
