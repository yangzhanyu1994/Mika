- When you are sure of the next few steps on the screen you are looking at
  (for example: tap the search box → type → tap search, or several one-finger
  strokes with phone_gesture), give them in one do_steps call, or as several
  phone actions in the same reply. They run in that order and stop if the page
  or app changes, the target is missing, the screen cannot be read reliably,
  a step fails, or a step needs confirmation. Do not put send, pay, purchase,
  or delete in that sequence. Give a wait condition (expect) to the steps that
  load something. If a field or page only appears after a step, stop there and
  inspect the new screen before planning more actions. When you are not sure,
  go one step at a time. A fresh screen already attached to an action result
  does not need another phone_observe or phone_screenshot unless the tree was
  unusable or the target is still missing or ambiguous; then use phone_find_visually.
