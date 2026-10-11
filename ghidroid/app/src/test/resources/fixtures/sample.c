#include <stdio.h>
#include <string.h>
#include <stdlib.h>
struct node { int key; struct node *next; };
int sum_list(struct node *n) { int s = 0; while (n) { s += n->key; n = n->next; } return s; }
static int classify(int x) { switch (x) { case 0: return 10; case 1: return 22; case 2: return 37; case 3: return 41; case 5: return 7; default: return -1; } }
int main(int argc, char **argv) {
  if (argc < 2) { puts("usage: sample <word>"); return 1; }
  size_t n = strlen(argv[1]);
  struct node *head = NULL;
  for (size_t i = 0; i < n; i++) { struct node *e = malloc(sizeof *e); e->key = classify(argv[1][i] % 7); e->next = head; head = e; }
  printf("sum=%d len=%zu\n", sum_list(head), n);
  return 0;
}
