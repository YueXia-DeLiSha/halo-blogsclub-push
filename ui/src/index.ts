import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import { IconPlug } from '@halo-dev/components'
import PushView from './views/PushView.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/blogsclub-push',
        name: 'BlogsClubPush',
        component: PushView,
        meta: {
          title: 'BlogsClub 推送',
          searchable: true,
          menu: {
            name: 'BlogsClub 推送',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})