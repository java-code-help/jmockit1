/*
 * Copyright (c) 2006-2014 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.internal.expectations.mocking;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.*;
import static java.lang.reflect.Modifier.*;

import mockit.internal.expectations.injection.*;
import mockit.internal.state.*;
import mockit.internal.util.*;

import org.jetbrains.annotations.*;

import static mockit.external.asm.Opcodes.*;

@SuppressWarnings("UnnecessaryFullyQualifiedName")
public final class FieldTypeRedefinitions extends TypeRedefinitions
{
   private static final int FIELD_ACCESS_MASK = ACC_SYNTHETIC + ACC_STATIC;

   @Nullable private TestedClassInstantiations testedClassInstantiations;
   @NotNull private final Map<MockedType, InstanceFactory> mockInstanceFactories;
   @NotNull private final List<MockedType> mockFieldsNotSet;

   public FieldTypeRedefinitions(@NotNull Object objectWithMockFields)
   {
      Class<?> testClass = objectWithMockFields.getClass();
      TestRun.enterNoMockingZone();

      try {
         testedClassInstantiations = new TestedClassInstantiations();

         if (!testedClassInstantiations.findTestedAndInjectableFields(testClass)) {
            testedClassInstantiations = null;
         }

         mockInstanceFactories = new HashMap<MockedType, InstanceFactory>();
         mockFieldsNotSet = new ArrayList<MockedType>();
         redefineFieldTypes(testClass);
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }

   private void redefineFieldTypes(@NotNull Class<?> classWithMockFields)
   {
      Class<?> superClass = classWithMockFields.getSuperclass();

      if (
         superClass != null && superClass != Object.class &&
         superClass != mockit.Expectations.class && superClass != mockit.NonStrictExpectations.class
      ) {
         redefineFieldTypes(superClass);
      }

      Field[] fields = classWithMockFields.getDeclaredFields();

      for (Field candidateField : fields) {
         int fieldModifiers = candidateField.getModifiers();

         if ((fieldModifiers & FIELD_ACCESS_MASK) == 0) {
            redefineFieldType(candidateField, fieldModifiers);
         }
      }

      ensureThatTargetClassesAreInitialized();
   }

   private void redefineFieldType(@NotNull Field field, int modifiers)
   {
      MockedType mockedType = new MockedType(field);

      if (mockedType.isMockableType()) {
         boolean partialMocking = field.isAnnotationPresent(mockit.Tested.class);
         boolean needsValueToSet = !isFinal(modifiers) && !partialMocking;

         redefineFieldType(mockedType, partialMocking, needsValueToSet);

         if (!partialMocking) {
            registerCaptureOfNewInstances(mockedType);
         }
      }
   }

   private void redefineFieldType(@NotNull MockedType mockedType, boolean partialMocking, boolean needsValueToSet)
   {
      TypeRedefinition typeRedefinition = new TypeRedefinition(mockedType);
      boolean redefined;

      if (needsValueToSet) {
         InstanceFactory factory = typeRedefinition.redefineType();
         redefined = factory != null;

         if (redefined) {
            mockInstanceFactories.put(mockedType, factory);
         }
      }
      else {
         if (partialMocking) {
            redefined = typeRedefinition.redefineTypeForTestedField();
         }
         else {
            redefined = typeRedefinition.redefineTypeForFinalField();
         }

         if (redefined) {
            mockFieldsNotSet.add(mockedType);
         }
      }

      if (redefined) {
         addTargetClass(mockedType);
      }
   }

   private void registerCaptureOfNewInstances(@NotNull MockedType mockedType)
   {
      if (mockedType.getMaxInstancesToCapture() > 0) {
         if (captureOfNewInstances == null) {
            captureOfNewInstances = new CaptureOfNewInstancesForFields();
         }

         captureOfNewInstances.registerCaptureOfNewInstances(mockedType, null);
      }
   }

   public void assignNewInstancesToMockFields(@NotNull Object target)
   {
      TestRun.getExecutingTest().clearInjectableAndNonStrictMocks();
      createAndAssignNewInstances(target);
      obtainAndRegisterInstancesOfFieldsNotSet(target);
   }

   private void createAndAssignNewInstances(@NotNull Object target)
   {
      for (Entry<MockedType, InstanceFactory> metadataAndFactory : mockInstanceFactories.entrySet()) {
         MockedType mockedType = metadataAndFactory.getKey();
         InstanceFactory instanceFactory = metadataAndFactory.getValue();

         Object mock = assignNewInstanceToMockField(target, mockedType, instanceFactory);
         registerMock(mockedType, mock);
      }
   }

   @NotNull
   private Object assignNewInstanceToMockField(
      @NotNull Object target, @NotNull MockedType mockedType, @NotNull InstanceFactory instanceFactory)
   {
      Field mockField = mockedType.field;
      assert mockField != null;
      Object mock = FieldReflection.getFieldValue(mockField, target);

      if (mock == null) {
         try {
            mock = instanceFactory.create();
         }
         catch (NoClassDefFoundError e) {
            StackTrace.filterStackTrace(e);
            e.printStackTrace();
            throw e;
         }
         catch (ExceptionInInitializerError e) {
            StackTrace.filterStackTrace(e);
            e.printStackTrace();
            throw e;
         }

         FieldReflection.setFieldValue(mockField, target, mock);

         if (mockedType.getMaxInstancesToCapture() > 0) {
            assert captureOfNewInstances != null;
            CaptureOfNewInstancesForFields capture = (CaptureOfNewInstancesForFields) captureOfNewInstances;
            capture.resetCaptureCount(mockField);
         }
      }

      return mock;
   }

   private void obtainAndRegisterInstancesOfFieldsNotSet(@NotNull Object target)
   {
      for (MockedType metadata : mockFieldsNotSet) {
         assert metadata.field != null;
         Object mock = FieldReflection.getFieldValue(metadata.field, target);

         if (mock != null) {
            registerMock(metadata, mock);
         }
      }
   }

   @Nullable
   public TestedClassInstantiations getTestedClassInstantiations() { return testedClassInstantiations; }

   /**
    * Returns true iff the mock instance concrete class is not mocked in some test, ie it's a class
    * which only appears in the code under test.
    */
   public boolean captureNewInstanceForApplicableMockField(@NotNull Object mock)
   {
      if (captureOfNewInstances == null) {
         return false;
      }

      Object fieldOwner = TestRun.getCurrentTestInstance();
      return captureOfNewInstances.captureNewInstance(fieldOwner, mock);
   }

   @Override
   public void cleanUp()
   {
      TestRun.getExecutingTest().clearCascadingTypes();
      super.cleanUp();
   }
}
