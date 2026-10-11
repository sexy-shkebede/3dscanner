typedef unsigned long size_t;
size_t strlen(const char *);
int puts(const char *);
void *malloc(size_t);

struct node { int key; struct node *next; };

int sum_list(struct node *n) { int s = 0; while (n) { s += n->key; n = n->next; } return s; }

int build(const char *word) {
  if (!word) { puts("no word given"); return -1; }
  size_t n = strlen(word);
  struct node *head = 0;
  for (size_t i = 0; i < n; i++) { struct node *e = malloc(sizeof *e); e->key = word[i]; e->next = head; head = e; }
  return sum_list(head);
}
