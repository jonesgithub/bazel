// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.pkgcache.TargetProvider;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility functions over test Targets that don't really belong in the base {@link Target}
 * interface.
 */
public final class TestTargetUtils {
  /**
   * Returns a predicate to be used for test size filtering, i.e., that only accepts tests of the
   * given size.
   */
  public static Predicate<Target> testSizeFilter(final Set<TestSize> allowedSizes) {
    return new Predicate<Target>() {
      @Override
      public boolean apply(Target target) {
        if (!(target instanceof Rule)) {
          return false;
        }
        return allowedSizes.contains(TestSize.getTestSize((Rule) target));
      }
    };
  }

  /**
   * Returns a predicate to be used for test timeout filtering, i.e., that only accepts tests of
   * the given timeout.
   **/
  public static Predicate<Target> testTimeoutFilter(final Set<TestTimeout> allowedTimeouts) {
    return new Predicate<Target>() {
      @Override
        public boolean apply(Target target) {
        if (!(target instanceof Rule)) {
          return false;
        }
        return allowedTimeouts.contains(TestTimeout.getTestTimeout((Rule) target));
      }
    };
  }

  /**
   * Returns a predicate to be used for test language filtering, i.e., that only accepts tests of
   * the specified languages. The reporter and the list of rule names are only used to warn about
   * unknown languages.
   */
  public static Predicate<Target> testLangFilter(List<String> langFilterList,
      EventHandler reporter, Set<String> allRuleNames) {
    final Set<String> requiredLangs = new HashSet<>();
    final Set<String> excludedLangs = new HashSet<>();

    for (String lang : langFilterList) {
      if (lang.startsWith("-")) {
        lang = lang.substring(1);
        excludedLangs.add(lang);
      } else {
        requiredLangs.add(lang);
      }
      if (!allRuleNames.contains(lang + "_test")) {
        reporter.handle(
            Event.warn("Unknown language '" + lang + "' in --test_lang_filters option"));
      }
    }

    return new Predicate<Target>() {
      @Override
      public boolean apply(Target rule) {
        String ruleLang = TargetUtils.getRuleLanguage(rule);
        return (requiredLangs.isEmpty() || requiredLangs.contains(ruleLang))
            && !excludedLangs.contains(ruleLang);
      }
    };
  }

  /**
   * Returns whether a test with the specified tags matches a filter (as specified by the set
   * of its positive and its negative filters).
   */
  public static boolean testMatchesFilters(Collection<String> testTags,
      Collection<String> requiredTags, Collection<String> excludedTags,
      boolean mustMatchAllPositive) {

    for (String tag : excludedTags) {
      if (testTags.contains(tag)) {
        return false;
      }
    }

    // Check required tags, if there are any.
    if (!requiredTags.isEmpty()) {
      if (mustMatchAllPositive) {
        // Require all tags to be present.
        for (String tag : requiredTags) {
          if (!testTags.contains(tag)) {
            return false;
          }
        }
        return true;
      } else {
        // Require at least one positive tag.
        for (String tag : requiredTags) {
          if (testTags.contains(tag)) {
            return true;
          }
        }
      }

      return false; // No positive tag found.
    }

    return true; // No tags are required.
  }

  /**
   * Returns a predicate to be used for test tag filtering, i.e., that only accepts tests that match
   * all of the required tags and none of the excluded tags.
   */
  // TODO(bazel-team): This also applies to non-test rules, so should probably be moved to
  // TargetUtils.
  public static Predicate<Target> tagFilter(List<String> tagFilterList) {
    Pair<Collection<String>, Collection<String>> tagLists = sortTagsBySense(tagFilterList);
    final Collection<String> requiredTags = tagLists.first;
    final Collection<String> excludedTags = tagLists.second;
    return new Predicate<Target>() {
      @Override
      public boolean apply(Target input) {
        if (!(input instanceof Rule)) {
          return false;
        }
        // Note that test_tags are those originating from the XX_test rule,
        // whereas the requiredTags and excludedTags originate from the command
        // line or test_suite rule.
        return testMatchesFilters(((Rule) input).getRuleTags(),
            requiredTags, excludedTags, false);
      }
    };
  }

  /**
   * Separates a list of text "tags" into a Pair of Collections, where
   * the first element are the required or positive tags and the second element
   * are the excluded or negative tags.
   * This should work on tag list provided from the command line
   * --test_tags_filters flag or on tag filters explicitly declared in the
   * suite.
   *
   * @param tagList A collection of text targets to separate.
   */
  public static Pair<Collection<String>, Collection<String>> sortTagsBySense(
      Iterable<String> tagList) {
    Collection<String> requiredTags = new HashSet<>();
    Collection<String> excludedTags = new HashSet<>();

    for (String tag : tagList) {
      if (tag.startsWith("-")) {
        excludedTags.add(tag.substring(1));
      } else if (tag.startsWith("+")) {
        requiredTags.add(tag.substring(1));
      } else if (tag.equals("manual")) {
        // Ignore manual attribute because it is an exception: it is not a filter
        // but a property of test_suite
        continue;
      } else {
        requiredTags.add(tag);
      }
    }
    return Pair.of(requiredTags, excludedTags);
  }

  /**
   * Returns the (new, mutable) set of test rules, expanding all 'test_suite' rules into the
   * individual tests they group together and preserving other test target instances.
   *
   * Method assumes that passed collection contains only *_test and test_suite rules. While, at this
   * point it will successfully preserve non-test rules as well, there is no guarantee that this
   * behavior will be kept in the future.
   *
   * @param targetProvider a target provider
   * @param eventHandler a failure eventHandler to report loading failures to
   * @param targets Collection of the *_test and test_suite configured targets
   * @return a duplicate-free iterable of the tests under the specified targets
   */
  public static ResolvedTargets<Target> expandTestSuites(TargetProvider targetProvider,
      EventHandler eventHandler, Iterable<? extends Target> targets, boolean strict,
      boolean keepGoing)
          throws TargetParsingException {
    Closure closure = new Closure(targetProvider, eventHandler, strict, keepGoing);
    ResolvedTargets.Builder<Target> result = ResolvedTargets.builder();
    for (Target target : targets) {
      if (TargetUtils.isTestRule(target)) {
        result.add(target);
      } else if (TargetUtils.isTestSuiteRule(target)) {
        result.addAll(closure.getTestsInSuite((Rule) target));
      } else {
        result.add(target);
      }
    }
    if (closure.hasError) {
      result.setError();
    }
    return result.build();
  }

  // TODO(bazel-team): This is a copy of TestsExpression.Closure with some minor changes; this
  // should be unified.
  private static final class Closure {
    private final TargetProvider targetProvider;

    private final EventHandler eventHandler;

    private final boolean keepGoing;

    private final boolean strict;

    private final Map<Target, Set<Target>> testsInSuite = new HashMap<>();

    private boolean hasError;

    public Closure(TargetProvider targetProvider, EventHandler eventHandler, boolean strict,
        boolean keepGoing) {
      this.targetProvider = targetProvider;
      this.eventHandler = eventHandler;
      this.strict = strict;
      this.keepGoing = keepGoing;
    }

    /**
     * Computes and returns the set of test rules in a particular suite.  Uses
     * dynamic programming---a memoized version of {@link #computeTestsInSuite}.
     */
    private Set<Target> getTestsInSuite(Rule testSuite) throws TargetParsingException {
      Set<Target> tests = testsInSuite.get(testSuite);
      if (tests == null) {
        tests = Sets.newHashSet();
        testsInSuite.put(testSuite, tests); // break cycles by inserting empty set early.
        computeTestsInSuite(testSuite, tests);
      }
      return tests;
    }

    /**
     * Populates 'result' with all the tests associated with the specified
     * 'testSuite'.  Throws an exception if any target is missing.
     *
     * CAUTION!  Keep this logic consistent with {@code TestsSuiteConfiguredTarget}!
     */
    private void computeTestsInSuite(Rule testSuite, Set<Target> result)
        throws TargetParsingException {
      List<Target> testsAndSuites = new ArrayList<>();
      // Note that testsAndSuites can contain input file targets; the test_suite rule does not
      // restrict the set of targets that can appear in tests or suites.
      testsAndSuites.addAll(getPrerequisites(testSuite, "tests"));
      if (testSuite.getRuleClassObject().hasAttr("suites", Type.LABEL_LIST)) {
        testsAndSuites.addAll(getPrerequisites(testSuite, "suites"));
      }

      // 1. Add all tests
      for (Target test : testsAndSuites) {
        if (TargetUtils.isTestRule(test)) {
          result.add(test);
        } else if (strict && !TargetUtils.isTestSuiteRule(test)) {
          // If strict mode is enabled, then give an error for any non-test, non-test-suite targets.
          eventHandler.handle(Event.error(testSuite.getLocation(),
              "in test_suite rule '" + testSuite.getLabel()
              + "': expecting a test or a test_suite rule but '" + test.getLabel()
              + "' is not one."));
          hasError = true;
          if (!keepGoing) {
            throw new TargetParsingException("Test suite expansion failed.");
          }
        }
      }

      // 2. Add implicit dependencies on tests in same package, if any.
      for (Target target : getPrerequisites(testSuite, "$implicit_tests")) {
        // The Package construction of $implicit_tests ensures that this check never fails, but we
        // add it here anyway for compatibility with future code.
        if (TargetUtils.isTestRule(target)) {
          result.add(target);
        }
      }

      // 3. Filter based on tags, size, env.
      filterTests(testSuite, result);

      // 4. Expand all suites recursively.
      for (Target suite : testsAndSuites) {
        if (TargetUtils.isTestSuiteRule(suite)) {
          result.addAll(getTestsInSuite((Rule) suite));
        }
      }
    }

    /**
     * Returns the set of rules named by the attribute 'attrName' of test_suite rule 'testSuite'.
     * The attribute must be a list of labels. If a target cannot be resolved, then an error is
     * reported to the environment (which may throw an exception if {@code keep_going} is disabled).
     */
    private Collection<Target> getPrerequisites(Rule testSuite, String attrName)
        throws TargetParsingException {
      try {
        List<Target> targets = new ArrayList<>();
        // TODO(bazel-team): This serializes package loading in some cases. We might want to make
        // this multi-threaded.
        for (Label label :
            NonconfigurableAttributeMapper.of(testSuite).get(attrName, Type.LABEL_LIST)) {
          targets.add(targetProvider.getTarget(eventHandler, label));
        }
        return targets;
      } catch (NoSuchThingException e) {
        if (keepGoing) {
          hasError = true;
          eventHandler.handle(Event.error(e.getMessage()));
          return ImmutableList.of();
        }
        throw new TargetParsingException(e.getMessage(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TargetParsingException("interrupted", e);
      }
    }

    /**
     * Filters 'tests' (by mutation) according to the 'tags' attribute, specifically those that
     * match ALL of the tags in tagsAttribute.
     *
     * @precondition {@code env.getAccessor().isTestSuite(testSuite)}
     * @precondition {@code env.getAccessor().isTestRule(test)} for all test in tests
     */
    private void filterTests(Rule testSuite, Set<Target> tests) {
      List<String> tagsAttribute =
          NonconfigurableAttributeMapper.of(testSuite).get("tags", Type.STRING_LIST);
      // Split the tags list into positive and negative tags
      Pair<Collection<String>, Collection<String>> tagLists = sortTagsBySense(tagsAttribute);
      Collection<String> positiveTags = tagLists.first;
      Collection<String> negativeTags = tagLists.second;

      Iterator<Target> it = tests.iterator();
      while (it.hasNext()) {
        Rule test = (Rule) it.next();
        AttributeMap nonConfigurableAttributes = NonconfigurableAttributeMapper.of(test);
        List<String> testTags =
            new ArrayList<>(nonConfigurableAttributes.get("tags", Type.STRING_LIST));
        testTags.add(nonConfigurableAttributes.get("size", Type.STRING));
        if (!includeTest(testTags, positiveTags, negativeTags)) {
          it.remove();
        }
      }
    }

    /**
     * Decides whether to include a test in a test_suite or not.
     * @param testTags Collection of all tags exhibited by a given test.
     * @param positiveTags Tags declared by the suite. A Test must match ALL of these.
     * @param negativeTags Tags declared by the suite. A Test must match NONE of these.
     * @return false is the test is to be removed.
     */
    private static boolean includeTest(Collection<String> testTags,
        Collection<String> positiveTags, Collection<String> negativeTags) {
      // Add this test if it matches ALL of the positive tags and NONE of the
      // negative tags in the tags attribute.
      for (String tag : negativeTags) {
        if (testTags.contains(tag)) {
          return false;
        }
      }
      for (String tag : positiveTags) {
        if (!testTags.contains(tag)) {
          return false;
        }
      }
      return true;
    }
  }
}
